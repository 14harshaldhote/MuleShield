"""Robustness checks for the payment model, beyond in-distribution validation (which is optimistic on
simulated data):

  1. Leave one scam type out: train without any example of it, then measure how much of it the model
     still catches. This is the realistic case of a new scam script the bank has never labelled.
  2. Blind spots: score the validation set with some signals removed, as happens in production:
     the payee is at another bank and nobody shares intelligence; the phone OS doesn't expose call or
     screen-sharing state; both.

    python3 ml/robustness.py --data target/ml/round2/training --out docs/results/robustness.json
"""
import argparse
import json
import pathlib

import numpy as np
import pandas as pd
import xgboost as xgb

from train import MONOTONE, LABEL_COLUMNS

PARAMS = {"objective": "binary:logistic", "eval_metric": "aucpr", "tree_method": "hist", "max_depth": 5, "eta": 0.05,
          "min_child_weight": 5, "subsample": 0.8, "colsample_bytree": 0.8, "lambda": 2.0, "seed": 42}
THRESHOLDS = {"stepUp": 0.30, "hold": 0.55}


def fit(train, features, rounds=350):
    d = xgb.DMatrix(train[features].to_numpy(np.float32), label=train["label"], weight=train["weight"],
                    feature_names=features, missing=np.nan)
    params = dict(PARAMS, monotone_constraints="(" + ",".join(str(MONOTONE["payment"].get(f, 0)) for f in features) + ")")
    return xgb.train(params, d, num_boost_round=rounds)


def predict(model, frame, features):
    return model.predict(xgb.DMatrix(frame[features].to_numpy(np.float32), feature_names=features, missing=np.nan))


def rates(prob, frame):
    scam = frame["label"].to_numpy() == 1
    w = frame["weight"].to_numpy()
    out = {}
    for name, t in THRESHOLDS.items():
        out[f"recallAt{name[0].upper() + name[1:]}"] = round(float((prob[scam] >= t).mean()), 4) if scam.any() else None
        out[f"legitAlertsPer1000At{name[0].upper() + name[1:]}"] = round(float(1000 * w[~scam][prob[~scam] >= t].sum() / w[~scam].sum()), 3)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True, type=pathlib.Path)
    ap.add_argument("--out", required=True, type=pathlib.Path)
    args = ap.parse_args()

    frame = pd.read_csv(args.data / "payments.csv")
    features = [c for c in frame.columns if c not in LABEL_COLUMNS]
    split = int(frame["day"].quantile(0.75))
    train, valid = frame[frame["day"] < split], frame[frame["day"] >= split].reset_index(drop=True)
    result = {"note": "Payment model, simulated data; thresholds are the India pack's step-up and hold cut-offs."}

    held_out = {}
    for typ in sorted(t for t in frame["typology"].unique() if t != "LEGIT"):
        model = fit(train[train["typology"] != typ], features)
        rows = valid[(valid["typology"] == typ) | (valid["label"] == 0)]
        held_out[typ] = rates(predict(model, rows, features), rows)
        held_out[typ]["validationRows"] = int((rows["label"] == 1).sum())
    result["leaveOneScamTypeOut"] = held_out

    full = fit(train, features)
    blind = {"all signals": valid.copy()}
    other_bank = valid.copy()
    other_bank["payee_mule_score"] = 0.0
    other_bank["payee_age_days"] = np.nan
    other_bank["payee_senders_24h"] = 0.0
    other_bank["payee_intel"] = 0.0
    blind["payee at another bank, no sharing"] = other_bank
    no_session = valid.copy()
    no_session["on_call"] = 0.0
    no_session["remote_access"] = 0.0
    blind["no call or screen-sharing signal from the phone"] = no_session
    both = other_bank.copy()
    both["on_call"] = 0.0
    both["remote_access"] = 0.0
    blind["both"] = both
    result["blindSpots"] = {}
    for name, rows in blind.items():
        entry = rates(predict(full, rows, features), rows)
        by_type = {}
        for typ in sorted(t for t in rows["typology"].unique() if t != "LEGIT"):
            sub = rows[rows["typology"] == typ]
            by_type[typ] = round(float((predict(full, sub, features) >= THRESHOLDS["stepUp"]).mean()), 4)
        entry["recallAtStepUpByType"] = by_type
        result["blindSpots"][name] = entry

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=1))


if __name__ == "__main__":
    main()
