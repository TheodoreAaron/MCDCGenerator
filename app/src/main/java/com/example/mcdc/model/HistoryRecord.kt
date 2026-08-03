package com.example.mcdc.model

/**
 * 一条历史记录：用户曾经生成过真值表的逻辑表达式。
 *
 * 只保存「表达式 + 生成参数 + 时间」三要素，不保存整张真值表——
 * 点开历史项时再根据 (expression, startFromTrue) 实时重新生成 [McdcResult]，
 * 这样既能完整回看真值表，又免去序列化/反序列化重型矩阵、且结果永远与当前算法一致。
 *
 * @param expression     原始表达式文本（已 trim）
 * @param startFromTrue  生成时的「起始基准」：true=从 1 开始，false=从 0 开始
 * @param createdAt      生成时间戳（毫秒）。同时作为唯一键与排序依据（越大越新）
 */
data class HistoryRecord(
    val expression: String,
    val startFromTrue: Boolean,
    val createdAt: Long
)
