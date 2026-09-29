-- Everything one payment decision needs from Valkey, in a single round trip.
-- KEYS: 1 payer state, 2 payee state, 3 device usage (sorted set), 4-5 payee intel tokens (this and last month),
--       6 payee scam reports (sorted set)
-- ARGV: 1 payer account, 2 now (ms), 3 device window start (ms), 4 report window start (ms), 5 device key TTL (s)
redis.call('ZADD', KEYS[3], ARGV[2], ARGV[1])
redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', '(' .. ARGV[3])
redis.call('EXPIRE', KEYS[3], ARGV[5])
return {
  redis.call('GET', KEYS[1]),
  redis.call('GET', KEYS[2]),
  redis.call('ZCARD', KEYS[3]),
  redis.call('GET', KEYS[4]),
  redis.call('GET', KEYS[5]),
  redis.call('ZCOUNT', KEYS[6], ARGV[4], '+inf')
}
