package prism.desktop

import prism.desktop.macos.QuickLookPictures
import prism.platform.Os
import prism.thumbnails.SystemPictures

/**
 * The system's own maker of pictures of files, where it has one: what makes a thumbnail of a file that nothing of
 * prism's does (ffmpeg is not installed, or does not read it). On a Mac, Quick Look, which makes Finder's; on Linux
 * there is none (the desktop's programs share a folder of thumbnails instead, which prism reads and writes itself);
 * on Windows, none yet.
 */
fun systemPictures(os: Os = Os.current): SystemPictures? =
	when (os) {
		Os.MacOS -> QuickLookPictures
		Os.Linux, Os.Windows -> null
	}
