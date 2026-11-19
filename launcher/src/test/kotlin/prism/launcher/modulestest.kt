package prism.launcher

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The launcher's modules, in its state alone: what they make of what is typed, where it is shown, and what is done. */
class ModulesTest {
	private val now = Instant.parse("2026-11-14T10:00:00Z")

	/** A module of the test's own, reached by `t ` and `>` and by anything that begins with `fire`: rows and a banner as told. */
	private class Tried(
		private val strength: Strength = Strength.Likely,
		private val key: BannerKey? = null,
	) : LauncherModule {
		override val id = ModuleId("tried")
		override val title = "Tried"
		override val reach = Reach.AlsoBare(listOf(Prefix.Word("t"), Prefix.Sign(">")))

		override fun bare(query: String): ModuleAnswer? =
			query.takeIf { it.startsWith("fire") }?.let {
				ModuleAnswer(
					strength,
					banner = Banner("loud: $it", key = key),
					rows =
						listOf(
							ModuleRow(
								"shout",
								it.uppercase(),
								"shouted",
								"Shout",
								ModuleAct.Copy(it.uppercase()),
							),
						),
				)
			}

		override fun view(text: String): ModuleView =
			ModuleView(
				"type to try",
				banner =
					Banner(
						"tried $text",
						key = BannerKey.Does("Copy", ModuleAct.Copy(text)),
					).takeIf {
						text.isNotEmpty()
					},
				rows =
					listOf(
						ModuleRow("more", "$text more", "", "Word", ModuleAct.Put("$text more")),
					),
			)
	}

	private fun state(vararg modules: LauncherModule, query: String = "") =
		LauncherState(
			installed = Installed.of(Programs.all),
			modules = Modules.of(modules.toList()),
			now = now,
		).let {
			if (query.isEmpty())
				it
			else
				step(it, LauncherEvent.Typed(query)).state
		}

	private fun LauncherState.after(vararg events: LauncherEvent): LauncherStep =
		events.fold(LauncherStep(this)) { at, event -> step(at.state, event) }

	@Test
	fun theCalculatorAnswersArithmeticAlongTheTopAndItsKeyCopiesIt() {
		val typed = state(CalculatorModule, query = "2+3*4")

		assertEquals("14", typed.shown?.banner?.title)
		assertEquals(
			"Copy",
			typed.shown
				?.banner
				?.key
				?.label,
		)
		// Not among what the arrows move through: Enter still starts what is found.
		assertTrue(typed.entries.none { it is Entry.Answer })

		val answered = typed.after(LauncherEvent.Answered)

		assertEquals(listOf(LauncherEffect.Copy("14"), LauncherEffect.Close), answered.effects)
	}

	@Test
	fun noArithmeticIsLeftToTheApplicationsAndADatesDashesAreAnsweredLast() {
		assertNull(state(CalculatorModule, query = "fire").shown)
		assertNull(state(CalculatorModule, query = "42").shown)

		val dated = state(CalculatorModule, query = "2024-11-18")

		assertNull(dated.shown)
		assertEquals("Calculator", dated.sections.last().title)
		assertEquals("= 1995", dated.entries.last().title)
	}

	@Test
	fun theCalculatorTypesAndOverflowsAreShownAndWhatIsWrongIsSaid() {
		val typed = state(CalculatorModule, query = "2147483647.toInt() + 1")

		assertEquals("-2147483648", typed.shown?.banner?.title)
		assertEquals("Int", typed.shown?.banner?.badge)
		assertTrue(
			typed.shown!!
				.banner.notes
				.single()
				.startsWith("Overflow: 2147483648 > 2147483647"),
		)

		val wrong = state(CalculatorModule, query = "sin(1e400)")

		assertEquals(Tone.Wrong, wrong.shown?.banner?.tone)
		assertNull(wrong.shown?.banner?.key)
	}

	@Test
	fun aPrefixOpensTheModulesViewWhereTheFieldIsItsAloneUntilItIsLeft() {
		val viewing = state(CalculatorModule, query = "=1+")

		assertEquals(Viewing(CalculatorModule.id, "1+"), viewing.viewing)
		assertEquals("1+", viewing.field)
		assertEquals("", viewing.query)
		// What is wrong is said in the view as it is typed: there is nothing else for it to be.
		assertEquals(Tone.Wrong, viewing.shown?.banner?.tone)
		assertTrue(viewing.sections.none { it.title == "Applications" })

		val word = state(CalculatorModule, query = "calc 2 ** 10")

		assertEquals("2 ** 10", word.field)
		assertEquals("1024", word.shown?.banner?.title)
		// Enter, in the view, is the banner's key.
		assertEquals(
			listOf(LauncherEffect.Copy("1024"), LauncherEffect.Close),
			word.after(LauncherEvent.Entered).effects,
		)

		val left = word.after(LauncherEvent.Dismissed).state

		assertNull(left.viewing)
		assertEquals("", left.field)
		assertNull(word.after(LauncherEvent.Typed(""), LauncherEvent.LeftView).state.viewing)

		// A word asked for in quotes is a word, and asks for no module.
		assertNull(state(CalculatorModule, query = "\"calc").viewing)
		assertNull(state(CalculatorModule, query = "calculator").viewing)
	}

	@Test
	fun inTheCalculatorsViewTheNameBeingTypedIsFinishedByTab() {
		val typed = state(CalculatorModule, query = "=1 + atan")

		assertTrue("atan2(y: Double, x: Double): Double" in typed.entries.map { it.title })

		val finished = typed.after(LauncherEvent.Completed).state

		assertEquals("1 + atan(", finished.field)
	}

	@Test
	fun aModulesRowsAreShownWhereItIsAsSureAsItIsAndAreChosenAsAnyResult() {
		fun sections(strength: Strength) =
			state(Tried(strength), query = "fire").sections.map { it.title }

		assertEquals("Tried", sections(Strength.Certain).first())
		assertEquals(
			listOf("Applications", "Tried"),
			sections(Strength.Likely).filter {
				it ==
					"Applications" ||
					it == "Tried"
			},
		)
		assertEquals("Tried", sections(Strength.Possible).last())

		val chosen =
			state(
				Tried(Strength.Certain),
				query = "fire",
			).after(LauncherEvent.Entered)

		assertEquals(listOf(LauncherEffect.Copy("FIRE"), LauncherEffect.Close), chosen.effects)
		// Only a certain module's banner is shown.
		assertNull(state(Tried(Strength.Likely), query = "fire").shown)
		assertEquals(
			"loud: fire",
			state(Tried(Strength.Certain), query = "fire").shown?.banner?.title,
		)
	}

	@Test
	fun aBannersKeyMayOpenAMenuOrTheModulesView() {
		val menu =
			state(
				Tried(
					Strength.Certain,
					BannerKey.Menu("More", listOf(ModuleChoice("Copy it", ModuleAct.Copy("it")))),
				),
				query = "fire",
			).after(LauncherEvent.Answered).state

		assertEquals(true, menu.menu?.ofBanner)
		assertEquals(
			listOf(LauncherEffect.Copy("it"), LauncherEffect.Close),
			menu.after(LauncherEvent.Entered).effects,
		)

		val viewed =
			state(
				Tried(Strength.Certain, BannerKey.View("Open")),
				query = "fire",
			).after(LauncherEvent.Answered).state

		assertEquals(Viewing(ModuleId("tried"), "fire"), viewed.viewing)
		assertEquals("tried fire", viewed.shown?.banner?.title)
	}

	@Test
	fun eachPrefixAsksForOneModule() {
		val other =
			object : LauncherModule {
				override val id = ModuleId("other")
				override val title = "Other"
				override val reach = Reach.ByPrefix(listOf(Prefix.Sign("=")))

				override fun bare(query: String): ModuleAnswer? = error("only by prefix")

				override fun view(text: String) = ModuleView("")
			}

		assertThrows<IllegalArgumentException> { Modules.of(listOf(CalculatorModule, other)) }
		// One reached by its prefix alone is never asked about what is typed bare.
		assertNull(
			state(
				Tried(),
				object : LauncherModule by other {
					override val reach = Reach.ByPrefix(listOf(Prefix.Word("o")))
				},
				query = "x",
			).shown,
		)
	}

	@Test
	fun aModuleIsFoundByItsNameOrItsPrefixAndOpenedByTabOrEnter() {
		val typed = state(CalculatorModule, query = "calc")

		assertTrue(Entry.Module(CalculatorModule) in typed.entries)
		assertEquals(
			Viewing(CalculatorModule.id, ""),
			typed.after(LauncherEvent.ModuleAsked).state.viewing,
		)

		val chosen =
			typed
				.after(
					LauncherEvent.Chosen(typed.entries.indexOf(Entry.Module(CalculatorModule))),
				).state

		assertEquals(Viewing(CalculatorModule.id, ""), chosen.viewing)
		assertTrue(
			Entry.Module(
				CalculatorModule,
			) in state(CalculatorModule, query = "calculator").entries,
		)
		// Not with nothing typed, nor by what does not fit its name.
		assertTrue(state(CalculatorModule).entries.none { it is Entry.Module })
		assertTrue(state(CalculatorModule, query = "fire").entries.none { it is Entry.Module })
	}
}
