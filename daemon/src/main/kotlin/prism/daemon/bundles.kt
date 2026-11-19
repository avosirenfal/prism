package prism.daemon

import prism.index.Index
import prism.index.Kind
import prism.macos.BundleDisk
import prism.macos.BundlePath
import prism.macos.Catalogue
import prism.shared.Published

/*
 * A Mac's applications as a daemon keeps them: what its index holds that is named as a bundle is, read whenever the
 * index is whole, and moved on by each batch of FSEvents' changes between ([Catalogue]). The index is told nothing of
 * this; the daemon tells this of the index, and of what its feed hears ([Telling]).
 */

/** The bundles [index] holds: each folder in it named as a bundle is (`Something.app`). */
fun bundlesOf(index: Index): Set<BundlePath> =
	index
		.entries()
		.asSequence()
		.filter { it.kind == Kind.Directory }
		.mapNotNull { entry -> entry.where.path?.let(BundlePath::of) }
		.toSet()

/** The catalogue of a Mac's bundles a daemon keeps, as [disk] is looked at between its readings of the index. */
class KeptCatalogue(
	private val disk: BundleDisk = BundleDisk.SYSTEM,
) {
	private val held = Published<Catalogue>(Catalogue.Unread)

	/** The catalogue as it is now: nothing yet, until the index is first whole. */
	val now: Catalogue get() = held.now

	/** What the daemon is to tell this of. */
	val telling: Telling =
		Telling(
			whole = { index -> held.update { read(bundlesOf(index)) } },
			heard = { events -> held.update { after(events, disk) } },
		)
}
