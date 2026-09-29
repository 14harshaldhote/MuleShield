#!/usr/bin/env bash
# Rebuilds both models from scratch: two rounds of simulated shadow-mode logs, because the payment
# model's "payee mule score" input comes from the mule model and must be the score it will see live.
set -euo pipefail
cd "$(dirname "$0")/.."
SIM="java -jar simulator/target/simulator-1.0.0.jar"
WORK=target/ml
COMMON="--seed 11 --customers 20000 --days 90 --warmup 15 --scam-rate 5 --start 2026-02-01"

echo "== round 1: shadow run with the rule-based mule scorer"
$SIM train $COMMON --out $WORK/round1
python3 ml/train.py --data $WORK/round1/training --kind mule --out $WORK/round1/models

echo "== round 2: shadow run with the round-1 mule model"
$SIM train $COMMON --mule-model $WORK/round1/models/mule-gbm.json --out $WORK/round2
python3 ml/train.py --data $WORK/round2/training --kind mule \
    --out muleshield-core/src/main/resources/models --parity-out muleshield-core/src/test/resources/parity
python3 ml/train.py --data $WORK/round2/training --kind payment \
    --out muleshield-core/src/main/resources/models --parity-out muleshield-core/src/test/resources/parity
