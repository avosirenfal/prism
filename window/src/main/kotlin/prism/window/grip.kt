package prism.window

/*
 * How an overlay that is a layer surface has the keyboard, and what it asks of the compositor for it, as one state.
 * Why there is anything to keep is in waylandoverlay.kt: compositors answer a surface that asks for the keyboard on
 * demand differently, so one that shares the keyboard asks, sees what came of it, and asks otherwise where that was
 * nothing. What is here is that alone, with no surface and no clock of its own: how the surface stands ([Grip]),
 * what its window hears ([Told]), and what follows ([Grip.told]), which says what to ask of the compositor, if
 * anything.
 */

/** How a layer surface asks for the keyboard, as layer-shell says it ([wire], `keyboard_interactivity`). */
internal enum class Interactivity(
	val wire: Int,
) {
	/** The keyboard is the surface's alone, until the surface is gone. */
	Exclusive(1),

	/** The keyboard is the surface's as a window's is: until something else is clicked. */
	OnDemand(2),
}

/** What a window that is a layer surface hears of its keyboard: when, by the clock that only counts. */
internal sealed interface Told {
	/** A moment has passed since the surface was first on the screen; whether it [has] the keyboard by now. */
	data class Waited(
		val has: Boolean,
	) : Told

	/** It was given the keyboard, [at] a time. */
	data class Given(
		val at: Long,
	) : Told

	/** The keyboard went elsewhere, [at] a time. */
	data class Taken(
		val at: Long,
	) : Told

	/** The surface is no more. */
	data object Closed : Told
}

/** How a layer surface stands with the keyboard: one of these, and so never two at once. */
internal sealed interface Grip {
	/** A surface that still is: it [asks] the compositor for the keyboard one way or the other. */
	sealed interface Live : Grip {
		val asks: Interactivity

		/** What the log says of a surface that has come to stand so. */
		val said: String
	}

	/** Exclusively, for good: an overlay that holds the keyboard, or one that lost it for sharing it. */
	data object Held : Live {
		override val asks get() = Interactivity.Exclusive
		override val said
			get() = "the keyboard was taken from it for sharing it, so it holds it for good"
	}

	/** On demand, as it was made, with what comes of that not yet seen: an overlay that shares the keyboard. */
	data object Asking : Live {
		override val asks get() = Interactivity.OnDemand
		override val said get() = "asks for the keyboard on demand"
	}

	/** Exclusively, only to be given the keyboard by a compositor that gave it nothing for asking on demand. */
	data object ToBeGiven : Live {
		override val asks get() = Interactivity.Exclusive
		override val said get() = "not given the keyboard for the asking, so it holds it"
	}

	/** On demand again, now that it has the keyboard, [since] when: what is clicked next takes it. */
	data class Shared(
		val since: Long,
	) : Live {
		override val asks get() = Interactivity.OnDemand
		override val said get() = "shares the keyboard, now that it has it"
	}

	/** The surface is gone: nothing is asked for it again. */
	data object Gone : Grip

	/**
	 * A grip as it became, and the same where the compositor is to be asked for it ([toAsk]): nothing is, where it
	 * asks what the one before it did, or is gone.
	 */
	data class Step(
		val grip: Grip,
		val toAsk: Live?,
	)

	/** What follows from [told]: how the surface stands from then, and what it asks the compositor, if anything new. */
	fun told(told: Told): Step =
		after(told).let { next ->
			Step(next, (next as? Live)?.takeIf { it.asks != (this as? Live)?.asks })
		}

	private fun after(told: Told): Grip =
		when (this) {
			Gone -> Gone
			is Live ->
				when (told) {
					Told.Closed -> Gone
					// Asked on demand, and without the keyboard a moment on: asked exclusively, to be given it.
					is Told.Waited ->
						if (!told.has && asks == Interactivity.OnDemand)
							ToBeGiven
						else
							this
					// Given it, having asked exclusively only for that: on demand again, from now.
					is Told.Given ->
						if (this == ToBeGiven)
							Shared(told.at)
						else
							this
					// Gone in the same breath as it was shared: the compositor's answer to sharing it, so never again.
					is Told.Taken ->
						if (this is Shared && told.at - since < TAKEN_AT_ONCE_NANOS)
							Held
						else
							this
				}
		}

	companion object {
		/**
		 * How soon after an overlay shares the keyboard its going is the compositor's doing, and not the user's: a
		 * compositor that will not have it shared takes it in the same breath; a person takes longer to click.
		 */
		const val TAKEN_AT_ONCE_NANOS = 300_000_000L

		/** How a surface stands as it is made: asking on demand where it [shares] the keyboard, holding it where not. */
		fun of(shares: Boolean): Live =
			if (shares)
				Asking
			else
				Held
	}
}
