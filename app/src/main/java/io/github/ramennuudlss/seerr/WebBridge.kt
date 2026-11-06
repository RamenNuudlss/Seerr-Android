package io.github.ramennuudlss.seerr

/**
 * Scripts the app runs inside the Seerr page, plus the state they report back.
 *
 * The page talks to the app through a WebMessageListener named [NAME] that is only
 * exposed to the configured server's origin.
 */
class WebBridge {
    @Volatile
    var pullAllowed: Boolean = true

    fun onMessage(message: String?) {
        when (message) {
            "pull:1" -> pullAllowed = true
            "pull:0" -> pullAllowed = false
        }
    }

    companion object {
        const val NAME = "SeerrAndroid"

        /**
         * Reports on every touch whether a pull-down should refresh the page. It shouldn't
         * while a modal/slide-over is open or the touched panel is scrolled down.
         */
        const val PULL_SCRIPT = """
(function () {
  if (window.__seerrAndroid || !window.SeerrAndroid) return;
  window.__seerrAndroid = true;
  document.addEventListener('touchstart', function (e) {
    try {
      var root = document.scrollingElement || document.documentElement;
      var ok = root.scrollTop <= 0;
      if (document.documentElement.style.overflow === 'hidden' ||
          document.body.style.overflow === 'hidden' ||
          document.querySelector('[role="dialog"][aria-modal="true"]')) ok = false;
      var n = e.target;
      while (ok && n && n !== document.body && n !== document.documentElement) {
        if (n.scrollTop > 0) ok = false;
        var s = window.getComputedStyle(n);
        if (s.position === 'fixed' && n.offsetHeight > window.innerHeight * 0.5) ok = false;
        n = n.parentElement;
      }
      window.SeerrAndroid.postMessage(ok ? 'pull:1' : 'pull:0');
    } catch (err) {}
  }, { passive: true, capture: true });
})();
"""

        /**
         * Back button: close an open Seerr modal or slide-over instead of leaving the page.
         * Seerr modals close on an outside click; slide-overs close on Escape. Returns
         * "true" if it tried. A second back press within a second falls through to normal
         * back navigation, in case the modal refused to close.
         */
        const val BACK_SCRIPT = """
(function () {
  var d = document.querySelectorAll('[role="dialog"][aria-modal="true"]');
  if (!d.length) return false;
  var now = Date.now();
  if (window.__seerrBackTry && now - window.__seerrBackTry < 1000) return false;
  window.__seerrBackTry = now;
  var top = d[d.length - 1];
  top.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true }));
  document.body.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
  return true;
})();
"""
    }
}
