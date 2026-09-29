"""Builds the fixture for GbmModelParityTest: a small XGBoost model on random data (with missing
values), plus XGBoost's own probabilities and Saabas contributions for rows it hasn't seen.

The Java evaluator must reproduce both to 1e-5. Run from the repo root:
    python3 ml/make_parity_fixture.py
"""
import json
import pathlib

import numpy as np
import xgboost as xgb

OUT = pathlib.Path("muleshield-core/src/test/resources/parity")
FEATURES = [f"f{i}" for i in range(12)]


def main():
    rng = np.random.default_rng(7)
    n = 4000
    x = rng.normal(size=(n, len(FEATURES))).astype(np.float32)
    x[rng.random(size=x.shape) < 0.08] = np.nan
    logit = 1.4 * np.nan_to_num(x[:, 0]) - 0.9 * np.nan_to_num(x[:, 3]) + 0.8 * (np.nan_to_num(x[:, 5]) > 0.5) \
        + 0.6 * np.isnan(x[:, 7]) - 1.0
    y = (rng.random(n) < 1 / (1 + np.exp(-logit))).astype(int)
    train = xgb.DMatrix(x[:3000], label=y[:3000], feature_names=FEATURES, missing=np.nan)
    booster = xgb.train({"objective": "binary:logistic", "max_depth": 4, "eta": 0.2, "base_score": 0.3,
                         "tree_method": "hist", "seed": 7}, train, num_boost_round=40)
    OUT.mkdir(parents=True, exist_ok=True)
    booster.save_model(str(OUT / "model.json"))

    test = xgb.DMatrix(x[3000:3200], feature_names=FEATURES, missing=np.nan)
    prob = booster.predict(test)
    contribs = booster.predict(test, pred_contribs=True, approx_contribs=True)
    rows = [{"x": [None if np.isnan(v) else float(v) for v in x[3000 + i]],
             "p": float(prob[i]),
             "contribs": [float(c) for c in contribs[i][:-1]],
             "bias": float(contribs[i][-1])} for i in range(len(prob))]
    (OUT / "expected.json").write_text(json.dumps({"features": FEATURES, "rows": rows}))
    print(f"wrote {len(rows)} rows")


if __name__ == "__main__":
    main()
