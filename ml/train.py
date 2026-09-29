"""Trains MuleShield's two models from simulator feature logs and exports them for the Java engine.

    python3 ml/train.py --data target/sim/round2/training --kind payment --out muleshield-core/src/main/resources/models

The feature columns come from the Java feature code itself (the simulator logs exactly what the
service computes), so there is no training/serving skew to manage by hand. The split is by time:
the model is validated on the last weeks it never saw, as it would be in production.

Model risk controls, the kind a bank's model validation team asks for:
  * monotone constraints: more scam reports, a live remote-access session or a higher mule score
    can never lower the risk, so no explanation ever reads "reported for scams, therefore safer";
  * sample weights restore the true base rate after legitimate rows were sampled, so the output is
    a probability the policy thresholds can be set on;
  * a model card (JSON) with data, parameters and validation metrics is written next to the model;
  * a parity file lets the Java evaluator prove it reproduces XGBoost's own predictions.
"""
import argparse
import datetime as dt
import json
import pathlib

import numpy as np
import pandas as pd
import xgboost as xgb
from sklearn.metrics import average_precision_score, roc_auc_score

# +1: risk can only rise with the feature; -1: only fall. Unlisted features are unconstrained.
MONOTONE = {
    "payment": {
        "remote_access": 1, "on_call": 1, "payee_mule_score": 1, "payee_reports_30d": 1, "payee_intel": 1,
        "payee_name_mismatch": 1, "payee_new": 1, "drain_ratio": 1,
        "device_age_hours": -1, "sim_change_hours": -1,
    },
    "mule": {
        "pass_through_1h_share": 1, "upstream_risk": 1, "device_accounts": 1, "reports_30d": 1, "intel": 1,
        "median_dwell_log": -1, "cash_out_share": 1,
    },
}
LABEL_COLUMNS = {"label", "typology", "day", "weight", "account"}


def load(data: pathlib.Path, kind: str) -> tuple[pd.DataFrame, list[str]]:
    frame = pd.read_csv(data / ("payments.csv" if kind == "payment" else "mules.csv"))
    features = [c for c in frame.columns if c not in LABEL_COLUMNS]
    return frame, features


def recall_at(prob, y, w, value, threshold):
    flagged = prob >= threshold
    positives = y == 1
    tp = (flagged & positives).sum()
    flagged_weight = w[flagged].sum()
    return {
        "threshold": threshold,
        "recall": float(tp / max(positives.sum(), 1)),
        "valueRecall": float(value[flagged & positives].sum() / max(value[positives].sum(), 1e-9)),
        "precision": float(w[flagged & positives].sum() / max(flagged_weight, 1e-9)),
        "alertRatePer1000": float(1000 * flagged_weight / w.sum()),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True, type=pathlib.Path)
    ap.add_argument("--kind", required=True, choices=["payment", "mule"])
    ap.add_argument("--out", required=True, type=pathlib.Path)
    ap.add_argument("--thresholds", default="0.3,0.55,0.9")
    ap.add_argument("--parity-out", type=pathlib.Path)
    args = ap.parse_args()

    frame, features = load(args.data, args.kind)
    split_day = int(frame["day"].quantile(0.75))
    train, valid = frame[frame["day"] < split_day], frame[frame["day"] >= split_day]
    if args.kind == "mule":
        # Rows of one account are correlated: keep every account wholly on one side of the split.
        valid_accounts = set(valid["account"])
        train = train[~train["account"].isin(valid_accounts)]

    def matrix(df):
        return xgb.DMatrix(df[features].to_numpy(dtype=np.float32), label=df["label"].to_numpy(),
                           weight=df["weight"].to_numpy(), feature_names=features, missing=np.nan)

    dtrain, dvalid = matrix(train), matrix(valid)
    constraints = "(" + ",".join(str(MONOTONE[args.kind].get(f, 0)) for f in features) + ")"
    params = {
        "objective": "binary:logistic",
        "eval_metric": ["logloss", "aucpr"],   # the last one drives early stopping
        "tree_method": "hist",
        "max_depth": 5,
        "eta": 0.05,
        "min_child_weight": 5,
        "subsample": 0.8,
        "colsample_bytree": 0.8,
        "lambda": 2.0,
        "monotone_constraints": constraints,
        "seed": 42,
    }
    booster = xgb.train(params, dtrain, num_boost_round=600, evals=[(dvalid, "valid")],
                        early_stopping_rounds=60, verbose_eval=False)
    booster = booster[: booster.best_iteration + 1]

    prob = booster.predict(dvalid)
    y = valid["label"].to_numpy()
    w = valid["weight"].to_numpy()
    if args.kind == "payment":
        value = np.power(10.0, valid["amount_scaled_log"].to_numpy())
    else:
        value = np.ones(len(valid))
    metrics = {
        "rocAuc": float(roc_auc_score(y, prob, sample_weight=w)),
        "prAuc": float(average_precision_score(y, prob, sample_weight=w)),
        "baseRate": float(w[y == 1].sum() / w.sum()),
        "atThresholds": [recall_at(prob, y, w, value, float(t)) for t in args.thresholds.split(",")],
    }
    if args.kind == "payment":
        by_type = {}
        for typ, grp in valid[valid["label"] == 1].groupby("typology"):
            p = booster.predict(matrix(grp))
            by_type[typ] = {"rows": int(len(grp)), "recallAtStepUp": float((p >= 0.3).mean()),
                            "recallAtHold": float((p >= 0.55).mean())}
        metrics["byTypology"] = by_type

    args.out.mkdir(parents=True, exist_ok=True)
    model_path = args.out / f"{args.kind}-gbm.json"
    booster.save_model(str(model_path))
    importance = booster.get_score(importance_type="total_gain")
    total = sum(importance.values()) or 1
    card = {
        "model": f"{args.kind}-gbm",
        "trainedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "data": {"source": str(args.data), "trainRows": int(len(train)), "validRows": int(len(valid)),
                 "trainPositives": int(train["label"].sum()), "validPositives": int(valid["label"].sum()),
                 "split": f"time: train days < {split_day}, validate days >= {split_day}",
                 "synthetic": True},
        "features": features,
        "monotoneConstraints": {f: c for f, c in MONOTONE[args.kind].items() if f in features},
        "params": {k: v for k, v in params.items() if k != "monotone_constraints"},
        "trees": booster.num_boosted_rounds(),
        "validation": metrics,
        "featureImportanceGain": {k: round(v / total, 4) for k, v in sorted(importance.items(), key=lambda kv: -kv[1])},
        "limitations": "Trained on simulated data (no public labelled UPI fraud data exists). Validate on the bank's "
                       "own labelled history before any production use; re-fit thresholds to its alert budget.",
    }
    (args.out / f"{args.kind}-model-card.json").write_text(json.dumps(card, indent=2))

    if args.parity_out:
        sample = valid.sample(n=min(300, len(valid)), random_state=1)
        d = xgb.DMatrix(sample[features].to_numpy(dtype=np.float32), feature_names=features, missing=np.nan)
        p = booster.predict(d)
        c = booster.predict(d, pred_contribs=True, approx_contribs=True)
        rows = [{"x": [None if np.isnan(v) else float(v) for v in sample[features].to_numpy(dtype=np.float32)[i]],
                 "p": float(p[i]), "contribs": [float(v) for v in c[i][:-1]], "bias": float(c[i][-1])}
                for i in range(len(sample))]
        args.parity_out.mkdir(parents=True, exist_ok=True)
        (args.parity_out / f"{args.kind}-expected.json").write_text(json.dumps({"features": features, "rows": rows}))

    print(json.dumps({"model": str(model_path), "trees": card["trees"], **{k: v for k, v in metrics.items() if k != "byTypology"}}, indent=1))


if __name__ == "__main__":
    main()
