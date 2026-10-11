package com.tripguard.app.domain.model

/**
 * Typed application result. The repository layer never throws to the UI; it returns a
 * [Failure] with a classified [AppError] so screens can render meaningful error states.
 */
sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

enum class ErrorKind {
    NETWORK,
    TIMEOUT,
    OFFLINE,
    SERVER,
    NOT_FOUND,
    PARSE,
    VALIDATION,
    UNKNOWN,
}

data class AppError(
    val kind: ErrorKind,
    val message: String,
    val cause: Throwable? = null,
)

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Failure -> this
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.data
