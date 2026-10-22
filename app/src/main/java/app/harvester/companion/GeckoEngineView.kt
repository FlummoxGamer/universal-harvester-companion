package app.harvester.companion

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import org.mozilla.geckoview.GeckoSession

class GeckoEngineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    internal val geckoView: NestedGeckoView = NestedGeckoView(context)

    var session: GeckoSession?
        get() = geckoView.session
        set(value) { geckoView.setSession(value) }

    init {
        addView(geckoView)
        isNestedScrollingEnabled = true
    }

    fun setSession(session: GeckoSession?) {
        geckoView.setSession(session)
    }

    fun releaseSession() {
        geckoView.releaseSession()
    }

    fun reload() = geckoView.reload()
    fun goBack() = geckoView.goBack()
    fun loadUri(uri: String) = geckoView.loadUri(uri)
    fun setVerticalClipping(h: Int) = geckoView.setVerticalClipping(h)
}
