package xyz.sakulik.d20.app.ui.base

/**
 * UI 状态标记接口
 * 代表一个页面在某一时刻的所有数据呈现（持久状态）
 */
interface UiState

/**
 * UI 一次性事件标记接口
 * 代表那些不应该被持久化、只触发一次的副作用（如导航、弹窗、Toast）
 */
interface UiEvent
