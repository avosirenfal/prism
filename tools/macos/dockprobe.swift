// How the Dock asks an application for its Dock menu, found out on a real Mac: whether it is an Apple event, which, and
// whether prism could ask another application (Terminal) for its menu the same way, and be answered.
//
//   swift tools/macos/dockprobe.swift 2>&1 | tee ~/dockprobe.log
//   swift tools/macos/dockprobe.swift com.googlecode.iterm2 2>&1 | tee ~/dockprobe.log    another application to ask
//   AEDebugReceives=1 AEDebugSends=1 swift tools/macos/dockprobe.swift 2>&1 | tee ~/dockprobe.log    every event, too
//
// It needs Swift, which Xcode's command line tools have (xcode-select --install). It is an application of its own, with
// an icon in the Dock (a plain one, named for the Swift that runs it) and a Dock menu of one item. Then:
//
//   1. Right-click (or long-click) its icon in the Dock. It says what it was asked by, as the Dock asked: the Apple event
//      being handled then, if one was, with its class and ID, and the calls that led to its delegate being asked.
//   2. Choose "Probe item" in that menu. It says the same of the item being chosen, and then sends a copy of what the
//      Dock asked it to the application named (Terminal, unless another is), and says what came back: that
//      application's Dock menu, if it answers one that asks as the Dock does. macOS may ask first whether the probe
//      can control it ("Automation"), which is that question; it can be allowed and later taken back in System
//      Settings › Privacy & Security › Automation.
//   3. Ctrl+C ends it. What it printed is in ~/dockprobe.log.
//
// It changes nothing: it makes no file but its log, and what it sends the other application is the one question.

import AppKit

let asked = CommandLine.arguments.dropFirst().first ?? "com.apple.Terminal"

/** A four-character code as it is written: 'aevt'. */
func written(_ code: UInt32) -> String {
	let bytes = [24, 16, 8, 0].map { UInt8((code >> UInt32($0)) & 0xFF) }

	return "'" + (String(bytes: bytes, encoding: .macOSRoman) ?? "\(code)") + "'"
}

/** An Apple event as it is said: its class and ID, and the whole of it, as Foundation describes one. */
func said(_ event: NSAppleEventDescriptor?) -> String {
	guard let event = event else { return "none: this was not asked by an Apple event" }

	return "class \(written(event.eventClass)), ID \(written(event.eventID))\n\(event)"
}

/** What is said at each step, marked to be found in the log. */
func step(_ title: String, _ lines: String...) {
	print("\n==== \(title)")
	lines.forEach { print($0) }
	fflush(stdout)
}

@MainActor
final class Probe: NSObject, NSApplicationDelegate {
	/** What the Dock last asked by, to be sent on. */
	private var dockAsked: NSAppleEventDescriptor?

	func applicationDidFinishLaunching(_ notification: Notification) {
		NSApp.setActivationPolicy(.regular)
		NSApp.activate(ignoringOtherApps: true)
		step(
			"Started",
			"macOS \(ProcessInfo.processInfo.operatingSystemVersionString), process \(ProcessInfo.processInfo.processIdentifier)",
			"Right-click this probe's icon in the Dock, then choose “Probe item”. The other application asked: \(asked)."
		)
	}

	func applicationDockMenu(_ sender: NSApplication) -> NSMenu? {
		let event = NSAppleEventManager.shared().currentAppleEvent

		dockAsked = event
		step(
			"The Dock asked for the menu",
			"Apple event being handled: \(said(event))",
			"Calls that led here:\n" + Thread.callStackSymbols.joined(separator: "\n")
		)

		let menu = NSMenu()
		let item = NSMenuItem(title: "Probe item", action: #selector(chosen(_:)), keyEquivalent: "")

		item.target = self
		menu.addItem(item)

		return menu
	}

	@objc func chosen(_ sender: Any?) {
		step(
			"An item was chosen",
			"Apple event being handled: \(said(NSAppleEventManager.shared().currentAppleEvent))",
			"Calls that led here:\n" + Thread.callStackSymbols.joined(separator: "\n")
		)

		guard let dockAsked = dockAsked else {
			step("Nothing sent", "The Dock asked for the menu by no Apple event, so there is none to send \(asked).")
			return
		}

		ask(asked, as: dockAsked)
	}

	/** The application [bundle] sent what [event] asks, as the Dock sent it here, and what it answered said. */
	private func ask(_ bundle: String, as event: NSAppleEventDescriptor) {
		let copy =
			NSAppleEventDescriptor(
				eventClass: event.eventClass,
				eventID: event.eventID,
				targetDescriptor: NSAppleEventDescriptor(bundleIdentifier: bundle),
				returnID: AEReturnID(kAutoGenerateReturnID),
				transactionID: AETransactionID(kAnyTransactionID)
			)

		// Its parameters as they were: a record's items, each by its keyword, counted from 1.
		if event.numberOfItems > 0 {
			for at in 1...event.numberOfItems {
				let keyword = event.keywordForDescriptor(at: at)

				if let value = event.paramDescriptor(forKeyword: keyword) {
					copy.setParam(value, forKeyword: keyword)
				}
			}
		}

		guard NSRunningApplication.runningApplications(withBundleIdentifier: bundle).isEmpty == false else {
			step("Nothing sent", "\(bundle) is not running: open it, and choose “Probe item” again.")
			return
		}

		step("Sending \(bundle) what the Dock sent here", said(copy))

		do {
			let reply = try copy.sendEvent(options: [.waitForReply], timeout: 10)

			step(
				"\(bundle) answered",
				said(reply),
				"Its direct object: \(String(describing: reply.paramDescriptor(forKeyword: AEKeyword(keyDirectObject))))"
			)
		} catch {
			step("\(bundle) did not answer", "\(error)")
		}
	}
}

// A script's top level runs on the main thread, but Swift does not take it to be the main actor's: it is said to be.
MainActor.assumeIsolated {
	// Held here for as long as the application runs, which is until the process ends: a delegate is not kept by it.
	let probe = Probe()

	NSApplication.shared.delegate = probe
	NSApplication.shared.run()
}
