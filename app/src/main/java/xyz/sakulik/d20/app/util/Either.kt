package xyz.sakulik.d20.app.util

/**
 * 简单的 Either 实现，用于函数式错误处理
 */
sealed class Either<out L, out R> {
    data class Left<out L>(val value: L) : Either<L, Nothing>()
    data class Right<out R>(val value: R) : Either<Nothing, R>()

    fun isRight(): Boolean = this is Right
    fun isLeft(): Boolean = this is Left

    fun getRightOrNull(): R? = (this as? Right)?.value
    fun getLeftOrNull(): L? = (this as? Left)?.value
}
