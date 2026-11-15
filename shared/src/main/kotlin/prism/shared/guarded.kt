package prism.shared

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.reflect.KProperty

/*
 * State shared between threads, each kind as a type that says what it guarantees and gives no other way in.
 *
 * - [Guarded]: state reached only under its lock. There is no lock to forget to take, or to take for the wrong
 *   field: the state is not in reach anywhere else.
 * - [Published]: a value its one writer swaps whole, which any thread reads without waiting for the writer. What is
 *   read is always a whole value, the one last set.
 * - [Once]: a value that is set once, by whichever thread comes first, and is then that for good.
 *
 * What is waited for from another thread is a future or a channel, and what many threads add to is one of
 * java.util.concurrent's collections: there is nothing to add to those here.
 */

/**
 * [state], reached only by [guarded], one thread at a time: for state that is changed in place (an object with
 * fields, a mutable map), and for several values that change together. The lock is this alone's, and is taken again
 * by the thread that has it, so what is done under it may itself ask for it.
 *
 * What [guarded] is given must not let the state out (kept in a field, handed to another thread): what it returns is
 * what was read of it, not it.
 */
class Guarded<T>(
	@PublishedApi internal val state: T,
) {
	@PublishedApi
	internal val lock = ReentrantLock()

	/** [body] done with the state, as the one thread that has it. */
	inline fun <R> guarded(body: T.() -> R): R = lock.withLock { state.body() }
}

/**
 * A value read by any thread without a lock ([now]), and swapped whole for another by its writer ([set], [update]):
 * a reader has the value last set, all of it, and never waits. For a value that is immutable, made anew to change.
 *
 * It says nothing of writers: where there is more than one, they are kept apart by something else (a [Guarded] they
 * write under), or use [update], which makes each change to what the last left.
 *
 * It is a property's delegate too: `var indexer by Published(first)` reads as [now] and assigns as [set].
 */
class Published<T>(
	first: T,
) {
	private val held = AtomicReference(first)

	/** The value last set. */
	val now: T get() = held.get()

	/** [value] from now on. */
	fun set(value: T) = held.set(value)

	/**
	 * What [change] makes of the value, from now on: what it then is. Made to what the last writer left, so [change]
	 * may be asked more than once where writers meet, and must do nothing but make the value.
	 */
	fun update(change: T.() -> T): T = held.updateAndGet { it.change() }

	/**
	 * [to] from now on, where the value is still [from] (that very one, not one equal to it): whether it was. What
	 * two threads would both do once the value is as it is, the one that is told it was does.
	 */
	fun swapped(from: T, to: T): Boolean = held.compareAndSet(from, to)

	operator fun getValue(owner: Any?, property: KProperty<*>): T = now

	operator fun setValue(owner: Any?, property: KProperty<*>, value: T) = set(value)
}

/**
 * A value set once ([set]), by whichever thread is first, and read by any ([value]): null until it is set, and then
 * what it was set to, for good.
 */
class Once<T : Any> {
	private val held = AtomicReference<T?>(null)

	/** What it was set to; null where it has not been. */
	val value: T? get() = held.get()

	/** Set to [value], where it was not set: whether this was what set it. */
	fun set(value: T): Boolean = held.compareAndSet(null, value)
}
