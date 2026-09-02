package com.aetheris.workflow.dto;

/**
 * 发布流转动作（审批通过等）的请求。
 *
 * @param remark 操作备注；当前仅日志留痕，表结构未设审批意见字段，落库需先扩列
 */
public record ReleaseActionRequest(String remark) {
}
