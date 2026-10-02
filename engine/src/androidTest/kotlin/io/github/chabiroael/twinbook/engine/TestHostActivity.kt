package io.github.chabiroael.twinbook.engine

import android.app.Activity
import android.os.Bundle
import org.mozilla.geckoview.GeckoView

/** A full-screen GeckoView for tests that need a visible session. */
class TestHostActivity : Activity() {
    lateinit var geckoView: GeckoView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        geckoView = GeckoView(this)
        setContentView(geckoView)
    }
}
