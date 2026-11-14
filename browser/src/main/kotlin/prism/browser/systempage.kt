package prism.browser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import prism.desktop.Boot
import prism.desktop.Bus
import prism.desktop.Drive
import prism.desktop.Part
import prism.desktop.Place
import prism.desktop.Reached
import prism.desktop.Storage
import prism.desktop.Store
import prism.desktop.StoreKind
import prism.preferences.Held
import java.nio.file.Path as FilePath

/*
 * The System page: the machine's drives, what is on each, and how full each is, where a folder's rows would be.
 *
 * How full is a fact of the room files share (prism.desktop's storage.kt says why it is grouped so), so each store (a
 * drive, or drives a pool spans) is a card, and its bar is the store's room: one block for each file system on it, as
 * wide as the file system is large, its used part in the file system's colour and its free part in a tint of it; a
 * partition with nothing mounted is grey, and room no partition takes is left empty. So one bar says both how full the
 * drive is and which of what is on it is large. Under the bar, a row for each block says what it is and what it holds,
 * a pool's datasets under the pool, each with what it holds of the pool; a file system mounted is gone to by a click.
 *
 * A store fuller than [NEARLY_FULL] says so in the danger colour, as its free room does. The page shows one kind of
 * store at a time (those in the machine, removable ones, the network's), chosen by the buttons beside its title; the
 * store the computer was started from has its icon in gold, which says so when the pointer rests on it; a store folds to
 * its bar by the arrow at its end; and right-clicked, a store offers its drives' devlinks to copy.
 */

/** How full a file system or a store is before what it has free is said in the danger colour. */
private const val NEARLY_FULL = 0.9

/** The least width a block of a bar is drawn, so a small partition beside a large one is still seen. */
private val LEAST_BLOCK = 4.dp

/** How tall a store's bar is, and how far apart its blocks are. */
private val BAR = 12.dp
private val BAR_GAP = 2.dp

/** A row's own bar, beside what it holds. */
private val ROW_BAR = 96.dp

/** A block narrower than this is drawn in one colour: its used and its free parts would be too thin to tell apart. */
private val SPLIT_BLOCK = 16.dp

/** The kinds of store, in the order their buttons are: each a tab of the page. */
private val KINDS = listOf(StoreKind.Internal, StoreKind.Removable, StoreKind.Network)

/** The hues a store's file systems are told apart by, in order: one for each, around again past the last. */
private fun Palette.hues(): List<Color> =
	listOf(accent, video, image, audio, folder, archive)

/**
 * What a row of something mounted does: a click opens it ([open]); right-clicked, it offers what [menu] gives for its
 * name and folder, through [menus].
 */
private class Rowing(
	val menus: ContextMenus<BrowserEvent>,
	val open: (FilePath) -> Unit,
	val menu: (String, FilePath, Set<Held>) -> Menu<BrowserEvent>,
)

/**
 * The colour each of [store]'s parts is drawn in: its file systems each a hue; swap, what is not mounted, and a file
 * system that did not say how full it is, grey, as the bar draws what it does not know.
 */
private fun Palette.colorsOf(store: Store): List<Color> {
	val hues = hues()
	var next = 0

	return store.parts.map {
		when {
			it is Part.Volume && it.space != null -> hues[next++ % hues.size]
			it is Part.Unallocated -> sunken
			else -> writing
		}
	}
}

/**
 * The System page, as [system] has read it: nothing while it is first read; each store, the system's first; or, where
 * there is none to show, that.
 */
@Composable
internal fun SystemPane(
	state: BrowserState,
	system: Location.System,
	stops: Stops,
	modifier: Modifier,
	onEvent: (BrowserEvent) -> Unit,
) {
	val palette = LocalPalette.current
	val menus = rememberContextMenus<BrowserEvent>()
	val bindings = LocalBindings.current
	val storage = system.storage
	val query = state.arrangement.query

	// The kind of store shown, of those that match what is typed in the search field.
	val shown =
		storage?.let {
			Storage(
				it.stores.filter { store ->
					store.kind == system.shows
				},
			).narrowed(query)
		}

	// What a folder mounted offers, right-clicked: what a drive in the sidebar does, opened here or in a window of its own.
	val rowing =
		Rowing(menus, { onEvent(BrowserEvent.Opened(it)) }) { name, at, held ->
			contextMenuOf(
				state,
				Target.Sidebar(SidebarItem.Drive(Place(name, Place.Kind.Drive, at))),
				bindings,
				held = held,
			)
		}

	Box(
		modifier
			.fillMaxHeight()
			.background(palette.surface)
			.focusRequester(stops.listing)
			.focusable()
			.testTag("system"),
	) {
		when {
			storage == null || shown == null -> Box(Modifier.fillMaxSize())
			else ->
				ScrollArea(
					Modifier.fillMaxSize(),
					padding = PaddingValues(horizontal = Space.xl, vertical = Space.l),
					tag = "system-page",
				) {
					Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
						Overview(storage, system.shows, onEvent)
						when {
							shown.stores.isNotEmpty() ->
								for (store in shown.stores)
									StoreCard(
										store,
										folded = store.key in system.folded,
										rowing = rowing,
										onFold = { onEvent(BrowserEvent.FoldedStore(store.key)) },
									)
							query.isNotBlank() ->
								EmptyState(
									Glyph.Search,
									"No matches for “$query”",
									null,
									Modifier.fillMaxWidth(),
								)
							else ->
								EmptyState(
									glyphOf(system.shows),
									"No ${nounOf(system.shows)}",
									whenNoneOf(system.shows),
									Modifier.fillMaxWidth(),
								)
						}
					}
				}
		}
		ContextMenuHost(menus, onEvent)
	}
}

/**
 * [this] narrowed to what has [query] in what it is said to be, without case: a store whose name or what it is has it,
 * whole; otherwise those of its parts whose row has it, a pool with all its datasets where any of them has it. A store
 * left with nothing is left out. What the search field does on the System page.
 */
internal fun Storage.narrowed(query: String): Storage {
	val words = query.trim()

	if (words.isEmpty())
		return this

	fun has(vararg said: String?): Boolean =
		said.any {
			it?.contains(words, ignoreCase = true) ==
				true
		}

	fun Part.has(): Boolean =
		when (this) {
			is Part.Volume ->
				has(titleOf(this), subtitleOf(this), source) ||
					reached.any { has(it.name, it.at?.toString()) }
			is Part.Swap -> has("Swap", device)
			is Part.Unmounted -> has(label, type?.let(::typeNameOf), device, "not mounted")
			is Part.Unallocated -> has("Unallocated", drive)
		}

	return Storage(
		stores.mapNotNull { store ->
			if (has(titleOf(store), descriptionOf(store)))
				store
			else
				store.parts
					.filter { it.has() }
					.takeIf { it.isNotEmpty() }
					?.let { store.copy(parts = it) }
		},
	)
}

/**
 * The page's title, and beside it a button for each kind of store, the one [shows] lit (each says what it shows, and
 * how many, as the pointer rests on it); under it what the stores make together, of every kind.
 */
@Composable
private fun Overview(
	storage: Storage,
	shows: StoreKind,
	onEvent: (BrowserEvent) -> Unit,
) {
	val type = LocalType.current

	Column(
		Modifier.padding(bottom = Space.xs),
		verticalArrangement = Arrangement.spacedBy(Space.xxs),
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text("Drives and file systems", style = type.headline)
			Row(
				Modifier.padding(start = Space.l),
				horizontalArrangement = Arrangement.spacedBy(Space.xs),
			) {
				for (kind in KINDS) {
					val count = storage.stores.count { it.kind == kind }

					ToolButton(
						glyphOf(kind),
						"${titleOf(kind)} ($count)",
						enabled = true,
						Modifier.testTag("stores:$kind"),
						selected = kind == shows,
					) { onEvent(BrowserEvent.ShowedStores(kind)) }
				}
			}
		}
		Text(overviewOf(storage), style = type.muted)
	}
}

/** What the tab of [kind] shows, as its button says it. */
private fun titleOf(kind: StoreKind): String =
	when (kind) {
		StoreKind.Internal -> "Drives in this computer"
		StoreKind.Removable -> "Removable drives"
		StoreKind.Network -> "Network shares"
	}

/** What a store of [kind] is, of none of them: "No removable drives". */
private fun nounOf(kind: StoreKind): String =
	when (kind) {
		StoreKind.Internal -> "drives in this computer"
		StoreKind.Removable -> "removable drives"
		StoreKind.Network -> "network shares"
	}

/** What a tab of [kind] with nothing in it says of when it would have something. */
private fun whenNoneOf(kind: StoreKind): String =
	when (kind) {
		StoreKind.Internal -> "No internal drives are mounted."
		StoreKind.Removable -> "USB drives and memory cards appear here when connected."
		StoreKind.Network -> "Network shares appear here when mounted."
	}

/** "4 drives and 2 network shares · 2.4 TB free of 9.6 TB": what the System page shows, together. */
internal fun overviewOf(storage: Storage): String {
	val drives = storage.stores.sumOf { it.drives.size }
	val shares =
		storage.stores
			.filter {
				it.kind == StoreKind.Network
			}.sumOf { it.parts.size }

	val counted =
		listOfNotNull(
			counted(drives, "drive").takeIf { drives > 0 },
			counted(shares, "network share").takeIf { shares > 0 },
		).joinToString(" and ")

	return listOf(
		counted,
		"${sizeOf(
			storage.stores.sumOf { it.free },
		)} free of ${sizeOf(storage.stores.sumOf { it.size })}",
	).joinToString("  ·  ")
}

/** What the status line says of the System page: what it shows together, or that it is being read. */
internal fun statusOf(system: Location.System): String =
	system.storage?.let(::overviewOf) ?: "Loading…"

private fun counted(count: Int, noun: String): String =
	if (count ==
		1
	)
		"1 $noun"
	else
		"$count ${noun}s"

/**
 * A store: what it is (its drives, or the network), how much it has free, its bar, and a row for each part of it, in
 * the bar's colours, unless it is [folded] to its bar ([onFold] folds and unfolds it). A row of a file system mounted
 * does as [rowing] says; the store itself, right-clicked, offers its drives' devlinks to copy.
 */
@Composable
private fun StoreCard(
	store: Store,
	folded: Boolean,
	rowing: Rowing,
	onFold: () -> Unit,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val colors = remember(store, palette) { palette.colorsOf(store) }
	val full = store.fullness
	val title = titleOf(store)

	Card(Modifier.testTag("store:$title")) {
		CardItem(Modifier.contextMenu(rowing.menus) { devlinksOf(store) }) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(Space.m),
			) {
				StoreIcon(store)
				Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
					Text(title, style = type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
					Text(
						descriptionOf(store),
						style = type.muted,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
				// What can be written to it, of all of it; of a store of images and the like, that it can only be read.
				Column(
					horizontalAlignment = Alignment.End,
					verticalArrangement = Arrangement.spacedBy(Space.xxs),
				) {
					when {
						store.writable -> {
							Text(
								"${sizeOf(store.free)} free",
								style = type.strong.tabular(),
								color =
									if (full >= NEARLY_FULL)
										palette.dangerText
									else
										palette.text,
							)
							Text("of ${sizeOf(store.size)}", style = type.muted.tabular())
						}
						store.parts.any { it is Part.Volume } -> {
							Text("Read-only", style = type.strong)
							Text(sizeOf(store.size), style = type.muted.tabular())
						}
						// Nothing of it mounted (an image written to a stick, a drive not in use): what it holds unknown.
						else -> {
							Text("Not mounted", style = type.strong)
							Text(sizeOf(store.size), style = type.muted.tabular())
						}
					}
				}
				ToolButton(
					if (folded) Glyph.Chevron else Glyph.ChevronDown,
					if (folded)
						"Show partitions"
					else
						"Hide partitions",
					enabled = true,
					Modifier.testTag("fold:${store.key}"),
					onClick = onFold,
				)
			}
			StoreBar(store, colors, Modifier.padding(top = Space.xs))
		}
		if (!folded)
			store.parts.forEachIndexed { index, part ->
				PartRows(part, colors[index], rowing)
			}
	}
}

/**
 * A store's icon: the kind of store it is, in gold where it is the one the computer was started from, which says so,
 * and how that is known, when the pointer rests on it.
 */
@Composable
private fun StoreIcon(store: Store) {
	val palette = LocalPalette.current
	val type = LocalType.current

	@Composable
	fun Icon(color: Color, modifier: Modifier = Modifier) =
		Box(
			modifier
				.size(type.control + Space.xs)
				.background(
					if (store.boot != null)
						palette.folder.copy(alpha = 0.16f)
					else
						palette.sunken,
					RoundedCornerShape(Radius.medium),
				),
			contentAlignment = Alignment.Center,
		) {
			GlyphIcon(glyphOf(store.kind), color, type.icon * 1.25f)
		}

	when (store.boot) {
		null -> Icon(palette.textMuted)
		Boot.Firmware ->
			Tip("Boot drive") {
				Icon(palette.folder, Modifier.testTag("booted"))
			}
		Boot.Root ->
			Tip(
				"System drive (the boot drive isn't recorded)",
			) {
				Icon(palette.folder, Modifier.testTag("booted"))
			}
	}
}

/**
 * What a store offers right-clicked: each of its drives' devlinks to copy (its path in /dev/disk/by-id, what a pool or
 * a mount table is best given); nothing for a store of no drive that has one.
 */
private fun devlinksOf(store: Store): Menu<BrowserEvent>? {
	val linked = store.drives.mapNotNull { drive -> drive.link?.let { drive.name to it } }

	return linked.takeIf { it.isNotEmpty() }?.let {
		menu {
			for ((name, link) in linked)
				item(
					if (linked.size == 1)
						"Copy device path"
					else
						"Copy device path of $name",
					BrowserEvent.CopiedPaths(listOf(FilePath.of(link))),
				)
		}
	}
}

/**
 * How full [this] store is: what of its file systems' room is used, of all of it, those that can only be read left
 * out (they are full as they were made); 0 for one with none read.
 */
private val Store.fullness: Double
	get() {
		val spaces =
			parts
				.filterIsInstance<Part.Volume>()
				.filterNot {
					it.readOnly
				}.mapNotNull { it.space }
		val total = spaces.sumOf { it.total }

		return if (total == 0L)
			0.0
		else
			spaces.sumOf { it.used }.toDouble() / total
	}

private fun glyphOf(kind: StoreKind): Glyph =
	when (kind) {
		StoreKind.Internal -> Glyph.Drive
		StoreKind.Removable -> Glyph.Removable
		StoreKind.Network -> Glyph.Network
	}

/**
 * What a store is called: its drive's model ("Samsung SSD 980 PRO 1TB"); drives alike by how many and what
 * ("2 × WDC WD40EFRX-68N"); unlike, each; the network; or, on no drive known, what is on it.
 */
internal fun titleOf(store: Store): String {
	val drives = store.drives
	val models = drives.map { it.model ?: "Drive ${it.name}" }

	return when {
		store.kind == StoreKind.Network -> "Network"
		drives.isEmpty() ->
			(store.parts.firstOrNull() as? Part.Volume)?.let { it.label ?: it.source } ?: "Storage"
		drives.size == 1 -> models.single()
		models.distinct().size == 1 -> "${drives.size} × ${models.first()}"
		else -> models.joinToString(" + ")
	}
}

/**
 * What a store is said to be under its name: what kind of drive (an NVMe SSD, hard disks, a USB drive), the kernel's
 * names for them, and how large; or how many shares the network's has.
 */
internal fun descriptionOf(store: Store): String {
	val drives = store.drives

	if (store.kind == StoreKind.Network)
		return counted(store.parts.size, "share")
	if (drives.isEmpty())
		return "On drives not listed by this computer"

	val sizes = drives.map { sizeOf(it.size) }.distinct()

	return listOfNotNull(
		kindOf(drives),
		drives.joinToString(", ") { it.name },
		when {
			sizes.size > 1 -> sizes.joinToString(" + ")
			drives.size > 1 -> "${sizes.single()} each"
			else -> sizes.single()
		},
	).joinToString("  ·  ")
}

/** What kind of drive [drives] are, as one says it of them: "NVMe SSD", "Hard disks", "USB drive". */
private fun kindOf(drives: List<Drive>): String {
	val many = drives.size > 1

	fun of(drive: Drive): String =
		when (drive.bus) {
			Bus.Nvme -> "NVMe SSD"
			Bus.Usb -> if (many) "USB drives" else "USB drive"
			Bus.Sd -> if (many) "SD cards" else "SD card"
			// A virtual disk says it spins, whatever it is on: it is said to be what it is.
			Bus.Virtual -> if (many) "Virtual disks" else "Virtual disk"
			Bus.Sata, Bus.Other ->
				when {
					drive.spins && many -> "Hard disks"
					drive.spins -> "Hard disk"
					else -> "SSD"
				}
		}

	return drives.map(::of).distinct().joinToString(" + ")
}

/**
 * A store's bar: its room, one block for each of its parts, as wide as the part is large (but never so narrow it
 * cannot be seen): a file system's used part in its colour and its free part in a tint of it; swap's the same in grey;
 * a partition with nothing mounted grey; room no partition takes, nothing, on the bar's track.
 */
@Composable
private fun StoreBar(store: Store, colors: List<Color>, modifier: Modifier = Modifier) {
	val palette = LocalPalette.current
	val track = palette.sunken

	Canvas(modifier.fillMaxWidth().height(BAR).testTag("store-bar")) {
		val gap = BAR_GAP.toPx()

		val widths =
			widthsOf(
				store.parts.map { it.size },
				size.width - gap * (store.parts.size - 1),
				LEAST_BLOCK.toPx(),
			)

		val round = CornerRadius(size.height / 2)

		clipPath(
			Path().apply {
				addRoundRect(
					androidx.compose.ui.geometry
						.RoundRect(0f, 0f, size.width, size.height, round),
				)
			},
		) {
			drawRect(track)

			var x = 0f

			store.parts.forEachIndexed { index, part ->
				val width = widths[index]

				block(part, colors[index], palette, Offset(x, 0f), Size(width, size.height))
				x += width + gap
			}
		}
	}
}

/** One part's block of a bar, at [at], [size] large. */
private fun DrawScope.block(
	part: Part,
	color: Color,
	palette: Palette,
	at: Offset,
	size: Size,
) {
	fun filled(fraction: Double, full: Color, rest: Color) {
		val used = (size.width * fraction.coerceIn(0.0, 1.0)).toFloat()

		drawRect(rest, at, size)
		drawRect(full, at, Size(used, size.height))
	}

	// Too narrow to show both its parts: one colour, its own.
	val split = size.width >= SPLIT_BLOCK.toPx()

	when (part) {
		is Part.Volume ->
			part.space?.let { space ->
				if (split)
					filled(
						space.used.toDouble() / space.total.coerceAtLeast(1),
						color,
						color.copy(alpha = 0.22f),
					)
				else
					drawRect(color, at, size)
			} ?: drawRect(palette.writing.copy(alpha = 0.25f), at, size)
		is Part.Swap ->
			if (split)
				filled(
					part.used.toDouble() / part.size.coerceAtLeast(1),
					color.copy(alpha = 0.7f),
					color.copy(alpha = 0.25f),
				)
			else
				drawRect(color.copy(alpha = 0.7f), at, size)
		is Part.Unmounted -> drawRect(color.copy(alpha = 0.45f), at, size)
		is Part.Unallocated -> Unit
	}
}

/**
 * The widths of blocks [sizes] large, shared out of [room]: each its share, but none less than [least], what that
 * takes from the rest taken from them in their shares.
 */
internal fun widthsOf(sizes: List<Long>, room: Float, least: Float): List<Float> {
	val total = sizes.sum().toDouble()

	if (total <= 0 || sizes.isEmpty())
		return sizes.map { room / sizes.size.coerceAtLeast(1) }

	val small = sizes.map { it / total * room < least }
	val left = room - least * small.count { it }
	val rest = sizes.filterIndexed { index, _ -> !small[index] }.sum().toDouble()

	return sizes.mapIndexed { index, size ->
		if (small[index] || rest <= 0)
			least
		else
			(size / rest * left).toFloat()
	}
}

/**
 * The row of [part], in [color]: what it is, and what it holds; a pool's datasets under it, each a row. A row of what
 * is mounted does as [rowing] says.
 */
@Composable
private fun PartRows(part: Part, color: Color, rowing: Rowing) {
	val palette = LocalPalette.current

	when (part) {
		is Part.Volume -> {
			val at = part.at

			// A pool's datasets keep room under the last of them, as a row keeps its own.
			Column(Modifier.fillMaxWidth().padding(bottom = if (part.pooled) Space.s else 0.dp)) {
				PartRow(
					Swatch.Filled(color),
					titleOf(part),
					subtitleOf(part),
					amountOf(part),
					part.space?.let { it.used.toDouble() / it.total.coerceAtLeast(1) },
					if (part.space == null || part.fullness >= NEARLY_FULL)
						palette.danger
					else
						color,
					warn = part.space == null || part.fullness >= NEARLY_FULL,
					at = at,
					rowing = rowing,
				)
				// A pool's datasets: what each holds of the pool, the largest first.
				if (part.pooled)
					part.reached.forEach { dataset ->
						PartRow(
							Swatch.None,
							dataset.at?.toString() ?: dataset.name,
							datasetSubtitleOf(dataset),
							sizeOf(dataset.holds ?: 0),
							part.space?.let { (dataset.holds ?: 0).toDouble() / it.total.coerceAtLeast(1) },
							color,
							warn = false,
							at = dataset.at,
							rowing = rowing,
							indent = true,
						)
					}
			}
		}
		is Part.Swap ->
			PartRow(
				Swatch.Filled(color),
				"Swap",
				part.device,
				"${sizeOf(part.used)} of ${sizeOf(part.size)} in use",
				part.used.toDouble() / part.size.coerceAtLeast(1),
				color,
				warn = false,
				at = null,
				rowing = rowing,
			)
		is Part.Unmounted ->
			PartRow(
				Swatch.Filled(color.copy(alpha = 0.45f)),
				part.label ?: "Not mounted",
				listOfNotNull(
					part.type?.let(::typeNameOf),
					part.device,
					"not mounted".takeIf { part.label != null },
				).joinToString("  ·  "),
				sizeOf(part.size),
				null,
				color,
				warn = false,
				at = null,
				rowing = rowing,
			)
		is Part.Unallocated ->
			PartRow(
				Swatch.Empty,
				"Unallocated",
				"Unpartitioned space on ${part.drive}",
				sizeOf(part.size),
				null,
				color,
				warn = false,
				at = null,
				rowing = rowing,
			)
	}
}

/** How a part's row is marked, to tell it in the bar. */
@Immutable
private sealed interface Swatch {
	data class Filled(
		val color: Color,
	) : Swatch

	/** Room nothing takes: an outline, as the bar leaves it empty. */
	data object Empty : Swatch

	/** Nothing: a dataset, under its pool's mark. */
	data object None : Swatch
}

/**
 * One row of a store's card: its [swatch], its [title] and [subtitle], and at its end its [amount] with a bar of
 * [fraction] (where there is one) in [barColor]; [warn] says the amount in the danger colour. Where the row is of
 * something mounted ([at]), it does as [rowing] says.
 */
@Composable
private fun PartRow(
	swatch: Swatch,
	title: String,
	subtitle: String,
	amount: String,
	fraction: Double?,
	barColor: Color,
	warn: Boolean,
	at: FilePath?,
	rowing: Rowing,
	indent: Boolean = false,
) {
	val palette = LocalPalette.current
	val type = LocalType.current
	val source = remember { MutableInteractionSource() }
	val felt = source.felt()
	val opens = at != null

	Row(
		Modifier
			.fillMaxWidth()
			.heightIn(
				min =
					if (indent)
						type.row
					else
						type.row + Space.m,
			).background(
				if (opens && felt.hovered)
					palette.hover
				else
					Color.Transparent,
			).then(
				at?.let { path ->
					Modifier
						.clickable(source, indication = null, onClickLabel = "Open") { rowing.open(path) }
						.contextMenu(rowing.menus) { held -> rowing.menu(title, path, held) }
				} ?: Modifier,
			).testTag("part:$title")
			.padding(
				start =
					if (indent)
						Space.l + Space.xl
					else
						Space.l,
				end = Space.l,
				top = if (indent) Space.xs else Space.s,
				bottom = if (indent) Space.xs else Space.s,
			),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Space.m),
	) {
		SwatchMark(swatch)
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
			Text(
				title,
				style = if (indent) type.body else type.strong,
				color =
					if (opens && felt.hovered)
						palette.accentText
					else
						palette.text,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			if (subtitle.isNotEmpty())
				Text(subtitle, style = type.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
		}
		Column(
			Modifier.widthIn(min = ROW_BAR),
			horizontalAlignment = Alignment.End,
			verticalArrangement = Arrangement.spacedBy(Space.xs),
		) {
			Text(
				amount,
				style = type.body.tabular(),
				color = if (warn) palette.dangerText else palette.text,
				maxLines = 1,
			)
			fraction?.let { RowBar(it, barColor) }
		}
	}
}

@Composable
private fun SwatchMark(swatch: Swatch) {
	val palette = LocalPalette.current
	val shape = RoundedCornerShape(3.dp)
	val side = 10.dp

	when (swatch) {
		is Swatch.Filled -> Box(Modifier.size(side).background(swatch.color, shape))
		Swatch.Empty -> Box(Modifier.size(side).border(1.dp, palette.borderStrong, shape))
		Swatch.None -> Box(Modifier.width(side))
	}
}

/** A row's own bar: [fraction] of it in [color], on the track. */
@Composable
private fun RowBar(fraction: Double, color: Color) {
	val track = LocalPalette.current.sunken

	Canvas(Modifier.width(ROW_BAR).height(4.dp)) {
		val round = CornerRadius(size.height / 2)

		drawRoundRect(track, cornerRadius = round)
		drawRoundRect(
			color,
			size =
				Size(
					(size.width * fraction.coerceIn(0.0, 1.0)).toFloat().coerceAtLeast(size.height),
					size.height,
				),
			cornerRadius = round,
		)
	}
}

/** How full [this] file system is, as it fills up; 0 where it did not say, or it can only be read. */
private val Part.Volume.fullness: Double
	get() =
		space?.takeUnless { readOnly }?.let {
			it.used.toDouble() /
				it.total.coerceAtLeast(1)
		}
			?: 0.0

/** A file system's row's title: its pool's name, or the folder it is mounted at. */
private fun titleOf(volume: Part.Volume): String =
	if (volume.pooled)
		volume.label ?: volume.source
	else
		volume.at?.toString() ?: volume.label ?: volume.source

/**
 * Under a file system's title: its label (where it is not the title), its type, what it is of (a partition, a share),
 * and where else it is reached (a btrfs's other subvolumes); for a pool, how many datasets it has.
 */
private fun subtitleOf(volume: Part.Volume): String {
	if (volume.pooled)
		return listOf(
			typeNameOf(volume.type) + " pool",
			counted(volume.reached.size, "dataset"),
		).joinToString("  ·  ")

	val elsewhere = volume.reached.flatMap { it.mounts }.filter { it != volume.at }

	return listOfNotNull(
		volume.label?.let { "“$it”" },
		typeNameOf(volume.type),
		volume.source.removePrefix("/dev/"),
		elsewhere.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "also at "),
	).joinToString("  ·  ")
}

/**
 * Under a dataset's title: its own name, where it is mounted elsewhere than its name says (`tank/media` at
 * `/tank/media` says nothing more), and the other folders it is mounted at; that it is not mounted, where it is not.
 */
private fun datasetSubtitleOf(dataset: Reached): String =
	if (dataset.at == null)
		"not mounted"
	else
		listOfNotNull(
			dataset.name.takeIf { dataset.at != FilePath.of("/" + dataset.name) },
			dataset.mounts
				.drop(1)
				.takeIf {
					it.isNotEmpty()
				}?.joinToString(", ", prefix = "also at "),
		).joinToString("  ·  ")

/** What a file system's row says it holds: what it has free, of how much; that it can only be read; that it did not answer. */
private fun amountOf(volume: Part.Volume): String =
	volume.space?.let {
		if (volume.readOnly)
			"${sizeOf(it.used)}, read-only"
		else
			"${sizeOf(it.free)} free of ${sizeOf(it.total)}"
	} ?: "Not responding"

/** A file system's type as one says it: "Btrfs", "NTFS", "NFS"; as the system names it, where this knows no other. */
internal fun typeNameOf(type: String): String =
	when (type) {
		"vfat", "msdos" -> "FAT"
		"ntfs", "ntfs3" -> "NTFS"
		"exfat" -> "exFAT"
		"btrfs" -> "Btrfs"
		"zfs" -> "ZFS"
		"xfs" -> "XFS"
		"f2fs" -> "F2FS"
		"nfs", "nfs4" -> "NFS"
		"cifs", "smb3", "smbfs" -> "SMB"
		"fuse.sshfs" -> "SSHFS"
		"fuse.rclone" -> "rclone"
		"swap" -> "Swap"
		"crypto_LUKS" -> "Encrypted (LUKS)"
		"LVM2_member" -> "LVM"
		"linux_raid_member" -> "RAID member"
		"zfs_member" -> "ZFS member"
		else -> type
	}
