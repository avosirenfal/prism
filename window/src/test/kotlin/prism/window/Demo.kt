package prism.window

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** A window to look at by hand: `./gradlew :window:demo`. */
@Suppress("DEPRECATION") // A value-based field, as the application's are.
fun main() {
	application("prism-demo") {
		var clicks by remember { mutableIntStateOf(0) }
		var text by remember { mutableStateOf("typed: ") }
		val focus = remember { FocusRequester() }

		Window(
			onCloseRequest = ::exitApplication,
			title = "demo",
			size = DpSize(400.dp, 300.dp),
		) {
			LaunchedEffect(Unit) { focus.requestFocus() }
			LaunchedEffect(Unit) {
				delay(System.getProperty("demo.millis")?.toLong() ?: Long.MAX_VALUE)
				; println("clicks=$clicks text=$text video=${window.video} handle=${window.handle}")
				exitApplication()
			}
			Column(Modifier.fillMaxSize().background(Color(0xFFEEEEF4)).padding(16.dp)) {
				Text("Hello from SDL, clicked $clicks times")
				Box(
					Modifier
						.size(120.dp, 48.dp)
						.background(
							if (clicks % 2 ==
								0
							)
								Color(0xFF3366CC)
							else
								Color(0xFFCC3366),
						).clickable { clicks++ },
				)
				BasicTextField(
					text,
					{ text = it },
					Modifier.focusRequester(focus).padding(top = 16.dp),
				)
			}
		}
	}
}
