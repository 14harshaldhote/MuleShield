#!/usr/bin/env bash
# Replays fresh worlds (different seeds from training, scammers who have adapted) under every strategy.
set -euo pipefail
cd "$(dirname "$0")/.."
SIM="java -jar simulator/target/simulator-1.0.0.jar"
OUT=${OUT:-docs/results}
MODELS="--payment-model muleshield-core/src/main/resources/models/payment-gbm.json --mule-model muleshield-core/src/main/resources/models/mule-gbm.json"
WORLD="--customers 20000 --days 75 --warmup 15 --scam-rate 3 --start 2026-06-01"

for seed in 21 22 23; do
  $SIM evaluate $WORLD $MODELS --seed $seed --adaptive --out $OUT --name adaptive-seed$seed
done
$SIM evaluate $WORLD $MODELS --seed 21 --out $OUT --name naive-seed21
$SIM evaluate $WORLD $MODELS --seed 21 --adaptive --pessimistic --out $OUT --name pessimistic-seed21
for pack in IN-RBI-2026 UK-PSR-2024 EU-IPR-2025 SG-SRF-2024 AU-SPF-2025; do
  $SIM evaluate $WORLD $MODELS --seed 21 --adaptive --pack $pack --strategies NONE,POLICY_ONLY,MULESHIELD_ML,RISK_BASED_ONLY \
      --out $OUT --name pack-$pack
done
