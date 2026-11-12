package prism.explorer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import prism.browser.CheckBox
import prism.browser.Dims
import prism.browser.Emphasis
import prism.browser.Field
import prism.browser.Glyph
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.PushButton
import prism.desktop.Application
import prism.desktop.Applications
import prism.desktop.FileTypes
import prism.desktop.Launched
import prism.desktop.MimeType

/*
 * Choosing an application: to open files with ("Open with…"), or to make the one that opens a type (the properties'
 * "Change…"). The applications the files' types say they open come first, then every other, by name; typing narrows
 * both. Opening files, the one chosen can be made the default for their type as well, as every desktop's chooser offers.
 */

/** A heading over the applications under it. */
data class Section(
	val heading: String,
	val applications: List<Application>,
)

/**
 * The applications offered for [choice]: those [recommended] for what is chosen for, and the [others]. What [filter]
 * leaves of them is shown, and [chosen] is one of those, or none where it leaves nothing; [always] is whether the one
 * chosen is to become the default too, which only files of one type can be given.
 */
data class Chooser(
	val choice: Choice,
	val recommended: List<Application>,
	val others: List<Application>,
	val filter: String = "",
	val chosen: Application? = recommended.firstOrNull() ?: others.firstOrNull(),
	val always: Boolean = false,
) {
	/** The sections the filter leaves, each with something in it. */
	val sections: List<Section> =
		listOf(Section("Recommended", recommended), Section("Other applications", others))
			.map { section ->
				section.copy(applications = section.applications.filter { it.matches(filter) })
			}.filter { it.applications.isNotEmpty() }

	/** Every application shown, in order. */
	val shown: List<Application> = sections.flatMap { it.applications }

	/** The type the one chosen can be made the default for, where it can be. */
	val defaultable: MimeType? =
		when (choice) {
			is Choice.Open -> choice.type
			is Choice.Default -> null
		}

	/** These, narrowed to [text]: what is chosen stays chosen while it is shown, and the first shown is chosen if not. */
	fun filtered(text: String): Chooser {
		val narrowed = copy(filter = text)

		return narrowed.copy(
			chosen =
				chosen?.takeIf { it in narrowed.shown } ?: narrowed.shown.firstOrNull(),
		)
	}

	/** These, with the application [by] rows below the one chosen (above, for a negative [by]) chosen, at either end. */
	fun moved(by: Int): Chooser {
		val at = shown.indexOf(chosen)

		return copy(chosen = shown.getOrNull((at + by).coerceIn(0, shown.lastIndex)) ?: chosen)
	}

	private fun Application.matches(text: String): Boolean =
		text.isBlank() || name.contains(text.trim(), ignoreCase = true) ||
			id.contains(text.trim(), ignoreCase = true)
}

/** The chooser for [choice]: the applications that open every type it is for recommended, and the rest after them. */
fun chooserOf(choice: Choice, applications: Applications, types: FileTypes): Chooser {
	val forTypes =
		when (choice) {
			is Choice.Open -> choice.paths.map(types::of).distinct()
			is Choice.Default -> listOf(choice.type)
		}

	val recommended =
		forTypes
			.map(applications::forType)
			.reduceOrNull { both, next -> both.filter { it in next } }
			.orEmpty()

	return Chooser(choice, recommended, applications.all().filterNot { it in recommended })
}

/** The heading over the applications: what they are chosen for. */
fun headingOf(choice: Choice, types: FileTypes): String =
	when (choice) {
		is Choice.Open ->
			choice.paths.singleOrNull()?.let { "Open “${it.fileName}” with:" }
				?: "Open ${choice.paths.size} files with:"
		is Choice.Default -> "What opens ${describedType(choice.type, types)}:"
	}

/**
 * The chooser for [choice] on [explorer]'s desktop: confirmed, the files are opened with the application chosen (made
 * their type's default as well, where that was asked), or it is made the type's default; closed then, or left open to
 * say why it could not be.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OpenWithView(choice: Choice, explorer: Explorer, onClose: () -> Unit) {
	val desktop = explorer.desktop
	val palette = LocalPalette.current
	val type = LocalType.current
	val scope = rememberCoroutineScope()
	val loaded by produceState<Chooser?>(null, choice) {
		value =
			withContext(Dispatchers.IO) { chooserOf(choice, desktop.applications, desktop.types) }
	}
	var edited by remember { mutableStateOf<Chooser?>(null) }
	var failure by remember { mutableStateOf<String?>(null) }
	val filter = remember { FocusRequester() }
	val chooser = edited ?: loaded ?: return Message("Finding the applications…")

	fun confirm() {
		val application = chooser.chosen ?: return

		scope.launch {
			failure =
				withContext(Dispatchers.IO) {
					try {
						confirmed(chooser, application, explorer)
					} catch (failed: IOException) {
						"Couldn't save the default: ${failed.message}"
					}
				}
			if (failure == null)
				onClose()
		}
	}

	LaunchedEffect(Unit) { filter.requestFocus() }

	Column(Modifier.fillMaxSize().padding(Dims.gap * 2)) {
		Text(headingOf(choice, desktop.types), style = type.heading)
		Spacer(Modifier.height(Dims.gap))
		Field(
			chooser.filter,
			{ edited = chooser.filtered(it) },
			Modifier.fillMaxWidth(),
			hint = "Search the applications",
			trailing = Glyph.Search,
			focus = filter,
			onKey = {
				when {
					it.type != KeyEventType.KeyDown -> false
					it.key == Key.DirectionDown -> true.also { edited = chooser.moved(1) }
					it.key == Key.DirectionUp -> true.also { edited = chooser.moved(-1) }
					it.key == Key.Enter || it.key == Key.NumPadEnter -> true.also { confirm() }
					else -> false
				}
			},
		)
		Spacer(Modifier.height(Dims.gap))
		LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
			chooser.sections.forEach { section ->
				item(key = section.heading) {
					Text(
						section.heading,
						style = type.muted,
						modifier = Modifier.padding(top = Dims.gap, bottom = 2.dp),
					)
				}
				items(section.applications, key = { section.heading + it.id }) { application ->
					val chosen = application == chooser.chosen

					Row(
						Modifier
							.fillMaxWidth()
							.height(Dims.row)
							.background(
								if (chosen) palette.chosen else palette.window,
								RoundedCornerShape(Dims.radius),
							).combinedClickable(
								remember { MutableInteractionSource() },
								indication = null,
								onDoubleClick = {
									edited = chooser.copy(chosen = application)
									confirm()
								},
							) { edited = chooser.copy(chosen = application) }
							.padding(horizontal = Dims.gap),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							application.name,
							style = type.body,
							maxLines = 1,
							modifier = Modifier.weight(1f),
						)
						Text(
							application.id.removeSuffix(".desktop"),
							style = type.muted,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.width(220.dp),
						)
					}
				}
			}
			if (chooser.shown.isEmpty())
				item { Text("No application is called that.", style = type.muted) }
		}
		chooser.defaultable?.let {
			CheckBox(
				"Always open ${describedType(it, desktop.types)} with it",
				chooser.always,
			) { always ->
				edited = chooser.copy(always = always)
			}
		}
		failure?.let { Text(it, style = type.body.copy(color = palette.pdf)) }
		Spacer(Modifier.height(Dims.gap))
		Row(
			Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(Dims.gap, Alignment.End),
		) {
			PushButton("Cancel", Emphasis.Secondary, enabled = true, onClick = onClose)
			PushButton(
				when (choice) {
					is Choice.Open -> "Open"
					is Choice.Default -> "Use it"
				},
				Emphasis.Primary,
				enabled = chooser.chosen != null,
			) { confirm() }
		}
	}
}

/** What confirming [chooser] with [application] did: nothing went wrong, or why it did. */
private fun confirmed(
	chooser: Chooser,
	application: Application,
	explorer: Explorer,
): String? =
	when (val choice = chooser.choice) {
		is Choice.Default -> null.also { explorer.setDefault(choice.type, application) }
		is Choice.Open -> {
			chooser.defaultable
				?.takeIf {
					chooser.always
				}?.let { explorer.setDefault(it, application) }

			when (
				val launched =
					explorer.desktop.launcher.launch(
						application,
						choice.paths,
						explorer.preferences.preferences.value.terminal,
					)
			) {
				is Launched.Started -> null
				is Launched.Failed -> "Couldn't start ${launched.what}: ${launched.why}"
			}
		}
	}

/** The window's title for [choice]. */
fun openWithTitleOf(choice: Choice): String =
	when (choice) {
		is Choice.Open -> "Open with"
		is Choice.Default -> "Default application"
	}
