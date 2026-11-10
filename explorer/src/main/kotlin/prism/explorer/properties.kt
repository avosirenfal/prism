package prism.explorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import prism.browser.CheckBox
import prism.browser.Dims
import prism.browser.Emphasis
import prism.browser.LocalPalette
import prism.browser.LocalType
import prism.browser.PushButton
import prism.browser.sizeOf
import prism.desktop.Contents
import prism.desktop.Facts
import prism.desktop.FileKind
import prism.desktop.FileTypes
import prism.desktop.MimeType
import prism.desktop.Permissions

/*
 * The properties of files, as Explorer's and Thunar's are: what each is (its name, type, place, size, and times), what
 * opens it, with a button to choose another, and, for one file, who may read, write and run it, changed as the boxes
 * are ticked. What is said is worked out from the file's facts by plain functions ([generalOf], [tallyOf]); the window
 * reads the facts, counts what folders hold while it shows the rest, and draws the lines.
 */

/** A line of what the properties say: what it is of, and what it says. */
data class Line(
	val label: String,
	val value: String,
)

/** What [facts] add up to: each file one file of its size, and each folder one folder and all that is in it. */
fun tallyOf(facts: List<Facts>, contents: (Path) -> Contents): Contents =
	facts
		.map {
			when (it.kind) {
				FileKind.Folder -> contents(it.path) + Contents(files = 0, folders = 1, bytes = 0)
				else -> Contents(files = 1, folders = 0, bytes = it.size)
			}
		}.fold(Contents(0, 0, 0), Contents::plus)

operator fun Contents.plus(other: Contents): Contents =
	Contents(files + other.files, folders + other.folders, bytes + other.bytes)

/**
 * What the General tab says of [facts] (one file's, or several): [counted] is what they hold, added up, or null while
 * it is being counted; [types] names their types; times are said in [zone].
 */
fun generalOf(
	facts: List<Facts>,
	counted: Contents?,
	types: FileTypes,
	zone: ZoneId,
): List<Line> {
	val one = facts.singleOrNull()
	val type =
		facts
			.map { it.type }
			.distinct()
			.singleOrNull()
			?.let { describedType(it, types) }
			?: "Several types"

	val location =
		facts
			.map { it.path.parent }
			.distinct()
			.singleOrNull()
			?.toString()
			?: "Several folders"

	// A folder alone holds what is counted; several things are what is counted.
	val held =
		counted
			?.let {
				if (one == null)
					it
				else
					it.copy(folders = it.folders - 1)
			}?.let { holding(it) }
			?: "Counting…"

	val anyFolder = facts.any { it.kind == FileKind.Folder }

	return listOfNotNull(
		if (one != null)
			Line("Name", one.path.fileName?.toString() ?: one.path.toString())
		else
			Line(
				"Selected",
				"${facts.size} items, ${holding(tallyOf(facts) { Contents(0, 0, 0) })}",
			),
		Line("Type", type),
		Line("Location", location),
		one?.target?.let { Line("Link to", it.toString()) },
		Line("Size", counted?.let { sizeLineOf(it.bytes) } ?: "Counting…"),
		Line(
			"Holds",
			held,
		).takeIf { one?.kind == FileKind.Folder || (one == null && anyFolder) },
		one?.let { Line("Created", dateLineOf(it.created, zone)) },
		one?.let { Line("Modified", dateLineOf(it.modified, zone)) },
		one?.let { Line("Accessed", dateLineOf(it.accessed, zone)) },
	)
}

/** "PNG image (image/png)", or the type alone where the desktop has no name for it; a folder is a folder. */
fun describedType(type: MimeType, types: FileTypes): String =
	when (type) {
		MimeType.FOLDER -> "Folder"
		else -> types.describe(type)?.let { "$it ($type)" } ?: type.toString()
	}

/** "4.2 MB (4,400,000 bytes)"; "6 bytes", where that says it already. */
fun sizeLineOf(bytes: Long): String =
	sizeOf(bytes).let {
		if (bytes < 1024)
			it
		else
			"$it (${"%,d".format(bytes)} bytes)"
	}

/** "120 files and 8 folders", "1 file", "nothing". */
private fun holding(contents: Contents): String =
	listOfNotNull(
		counting(contents.files, "file").takeIf { contents.files > 0 },
		counting(contents.folders, "folder").takeIf { contents.folders > 0 },
	).joinToString(" and ").ifEmpty { "nothing" }

private fun counting(count: Long, thing: String): String =
	"%,d $thing${if (count == 1L) "" else "s"}".format(count)

private val WHEN = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm:ss")

private fun dateLineOf(instant: Instant?, zone: ZoneId): String =
	instant?.let { WHEN.format(it.atZone(zone)) } ?: "Not known"

/** Whom a permission is for. */
enum class Who(
	val label: String,
) {
	Owner("Owner"),
	Group("Group"),
	Others("Others"),
}

/** What a permission allows. */
enum class Right {
	Read,
	Write,
	Run,
}

/** Whether these let [who] do [right]. */
fun Permissions.allows(who: Who, right: Right): Boolean = accessOf(who).allows(right)

/** These, with [who] allowed [right] or not, as [allowed] says. */
fun Permissions.with(who: Who, right: Right, allowed: Boolean): Permissions {
	val access = accessOf(who).with(right, allowed)

	return when (who) {
		Who.Owner -> copy(owner = access)
		Who.Group -> copy(group = access)
		Who.Others -> copy(others = access)
	}
}

private fun Permissions.accessOf(who: Who): Permissions.Access =
	when (who) {
		Who.Owner -> owner
		Who.Group -> group
		Who.Others -> others
	}

private fun Permissions.Access.allows(right: Right): Boolean =
	when (right) {
		Right.Read -> read
		Right.Write -> write
		Right.Run -> run
	}

private fun Permissions.Access.with(right: Right, allowed: Boolean): Permissions.Access =
	when (right) {
		Right.Read -> copy(read = allowed)
		Right.Write -> copy(write = allowed)
		Right.Run -> copy(run = allowed)
	}

/** The tabs of the properties: the permissions only for one file whose file system has them. */
private enum class Tab(
	val label: String,
) {
	General("General"),
	Permissions("Permissions"),
}

/** The window's title for the properties of [paths]. */
fun propertiesTitleOf(paths: List<Path>): String =
	paths.singleOrNull()?.let { "Properties of “${it.fileName ?: it}”" }
		?: "Properties of ${paths.size} items"

/**
 * The properties of [paths], read on [explorer]'s desktop: read again after their permissions change, and what opens
 * them again after a default is set. What folders hold is counted while the rest is shown.
 */
@Composable
fun PropertiesView(paths: List<Path>, explorer: Explorer, onClose: () -> Unit) {
	val desktop = explorer.desktop
	val palette = LocalPalette.current
	val type = LocalType.current
	val scope = rememberCoroutineScope()
	var readings by remember { mutableStateOf(0) }
	var tab by remember { mutableStateOf(Tab.General) }
	var refused by remember { mutableStateOf<String?>(null) }
	val defaults by explorer.defaults.collectAsState()

	val facts by produceState<List<Facts>?>(null, paths, readings) {
		value = withContext(Dispatchers.IO) { paths.mapNotNull(desktop.facts::of) }
	}
	val read = facts ?: return Message("Reading…")

	if (read.isEmpty())
		return Message("${if (paths.size == 1) "It is" else "They are"} no longer there.")

	// Counted once, however often the facts are read again for their permissions.
	val counted by produceState<Contents?>(null, paths) {
		value = withContext(Dispatchers.IO) { tallyOf(read, desktop.facts::contents) }
	}
	val one = read.singleOrNull()
	val common =
		read.map { it.type }.distinct().singleOrNull()?.takeIf {
			read.none {
				it.kind ==
					FileKind.Folder
			}
		}

	val opener by produceState<String?>(null, common, defaults) {
		value =
			common?.let {
				withContext(Dispatchers.IO) {
					desktop.applications.defaultFor(it)?.name
						?: "Nothing yet"
				}
			}
	}

	val tabs =
		if (one?.permissions != null)
			Tab.entries
		else
			listOf(Tab.General)

	Column(Modifier.fillMaxSize().padding(Dims.gap * 2)) {
		if (tabs.size > 1)
			Tabs(tabs, tab, Tab::label) { tab = it }
		Column(
			Modifier
				.weight(
					1f,
				).fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.padding(vertical = Dims.gap),
		) {
			when (tab) {
				Tab.General -> {
					val lines = generalOf(read, counted, desktop.types, ZoneId.systemDefault())

					lines.take(3).forEach { Labelled(it) }
					if (common != null)
						Labelled("Opens with") {
							Text(opener ?: "…", style = type.body, modifier = Modifier.weight(1f))
							PushButton("Change…", Emphasis.Secondary, enabled = true) {
								explorer.chooseDefault(common)
							}
						}
					lines.drop(3).forEach { Labelled(it) }
				}
				Tab.Permissions -> {
					val facts = one!!
					val permissions = facts.permissions!!
					val user = System.getProperty("user.name")
					val mine = user == facts.owner || user == "root"

					Labelled(Line("Owner", facts.owner ?: "Not known"))
					Labelled(Line("Group", facts.group ?: "Not known"))
					Spacer(Modifier.height(Dims.gap))
					PermissionGrid(permissions, facts.kind == FileKind.Folder, mine) { changed ->
						scope.launch {
							refused = withContext(Dispatchers.IO) { desktop.facts.permit(facts.path, changed) }
							readings++
						}
					}
					if (!mine)
						Text("Only ${facts.owner ?: "its owner"} can change these.", style = type.muted)
					refused?.let {
						Text("Couldn't change them: $it", style = type.body.copy(color = palette.pdf))
					}
				}
			}
		}
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
			PushButton("Close", Emphasis.Primary, enabled = true, onClick = onClose)
		}
	}
}

/** Who may read, write and run (for a folder, go into) the file, a box for each, ticked and unticked where [enabled]. */
@Composable
private fun PermissionGrid(
	permissions: Permissions,
	folder: Boolean,
	enabled: Boolean,
	onChange: (Permissions) -> Unit,
) {
	val type = LocalType.current
	val column = Modifier.width(96.dp)

	Row {
		Spacer(Modifier.width(LABEL))
		Right.entries.forEach {
			Text(
				if (it == Right.Run &&
					folder
				)
					"Go into"
				else
					it.name,
				style = type.heading,
				modifier = column,
			)
		}
	}
	Who.entries.forEach { who ->
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(who.label, style = type.muted, modifier = Modifier.width(LABEL))
			Right.entries.forEach { right ->
				CheckBox(
					"",
					permissions.allows(who, right),
					modifier = column,
					enabled = enabled,
				) { onChange(permissions.with(who, right, it)) }
			}
		}
	}
}

/** How wide the labels of the lines are. */
internal val LABEL = 112.dp

@Composable
internal fun Labelled(line: Line) =
	Labelled(line.label) {
		SelectionContainer(Modifier.weight(1f)) {
			Text(line.value, style = LocalType.current.body)
		}
	}

/** A line: [label], and beside it what [content] shows. */
@Composable
internal fun Labelled(
	label: String,
	content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
	Row(
		Modifier.fillMaxWidth().padding(vertical = 5.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(label, style = LocalType.current.muted, modifier = Modifier.width(LABEL))
		content()
	}
}

@Composable
internal fun Message(text: String) =
	Column(Modifier.fillMaxSize().padding(Dims.gap * 2)) {
		Text(text, style = LocalType.current.muted)
	}
