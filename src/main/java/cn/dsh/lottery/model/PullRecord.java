package cn.dsh.lottery.model;

/**
 * 抽奖流水记录（用于统计与回溯）。
 *
 * @param time     时间戳（毫秒）
 * @param pool     卡池 ID
 * @param amount   抽奖次数
 * @param currency 消耗货币 ID
 * @param cost     消耗总量
 * @param summary  奖品摘要，例如 {@code legendary_sword x1, 500 金币}
 */
public record PullRecord(long time, String pool, int amount, String currency, double cost, String summary) {
}
