package inc.pazuzu.imzo

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import inc.pazuzu.imzo.protocol.Approver
import inc.pazuzu.imzo.ui.ApproverViewModel
import inc.pazuzu.imzo.ui.ImzoApp
import java.lang.ref.WeakReference

class MainActivity : FragmentActivity() {
    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            // The view model outlives this activity (rotation, theme change), so the signer must not
            // capture `this`: it asks for whichever MainActivity is current when it needs a prompt.
            val signer = KeystoreSigner(applicationContext) {
                current?.get() ?: throw SigningFailed("Imzo is not in the foreground")
            }
            val approver = Approver(signer, PrefsCounterStore(applicationContext))
            return ApproverViewModel(approver) { signer.publicKey } as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        if (!BuildConfig.DEBUG) {
            // No screenshots, recents thumbnails or screen sharing of an order being approved.
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        val vm = ViewModelProvider(this, factory)[ApproverViewModel::class.java]
        setContent { ImzoApp(vm) }
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    private companion object {
        @Volatile
        var current: WeakReference<MainActivity>? = null
    }
}
