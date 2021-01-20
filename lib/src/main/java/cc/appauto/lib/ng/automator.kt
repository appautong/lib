package cc.appauto.lib.ng

import android.accessibilityservice.AccessibilityService
import com.alibaba.fastjson.JSONObject

class AutomationStep(val name: String, val automator: AppAutomator) {
    private var postActDelay: Long = AppAutomator.defaultPostActDelay
    private var act: ((AutomationStep) -> Unit)? = null
    private var exp: ((HierarchyTree, AutomationStep) -> Boolean)? = null
    private var retryCount: Int = AppAutomator.defaultRetryCount

    private var executed: Int = 0
    var expectedSuccess: Boolean = false
        private set

    var message: String? = null

    // delay given milli-seconds after did action to wait UI change taking place
    fun postActionDelay(ms: Long): AutomationStep {
        if (ms >= 0) postActDelay = ms
        return this
    }

    fun action(r: (AutomationStep) -> Unit): AutomationStep {
        this.act = r
        return this
    }

    fun expect(predicate: (tree: HierarchyTree, step: AutomationStep) -> Boolean): AutomationStep {
        this.exp = predicate
        return this
    }

    // retry the action n-times if expect predicate not matched
    fun retry(n: Int): AutomationStep {
        if (n >= 0) retryCount = n
        return this
    }

    // start the action-expect loop
    fun run(): Boolean {
        if (act == null) {
            message = "nil action"
            return false
        }
        do {
            executed++
            this.act!!(this)
            if (postActDelay > 0) sleep(postActDelay)
            if (exp == null) {
                // set expection result to true if no expect runnable
                expectedSuccess = true
                message = "no expection, set step successed"
                break
            } else {
                val tree = HierarchyTree.from(automator.srv) ?: continue
                val matched = this.exp!!(tree, this)
                tree.recycle()
                if (matched) {
                    expectedSuccess = true
                    message = "succeed in NO.$executed execution"
                    break
                }
            }
        } while(executed <= retryCount)
        if (executed > retryCount) message = "failed after tried $executed times"
        return expectedSuccess
    }
}

class AppAutomator(val srv: AccessibilityService) {
    private val privateData = JSONObject()

    var allStepsSucceed = false
    var message: String? = null
        private set

    val steps: MutableList<AutomationStep> = mutableListOf()

    operator fun get(key: String): Any? {
       return privateData.get(key)
    }
    operator fun set(key: String, value: Any) {
        privateData[key] = value
    }

    companion object {
        var defaultRetryCount: Int = 3
        var defaultPostActDelay: Long = 1000
    }

    fun newStep(name: String): AutomationStep {
        val step = AutomationStep(name, this)
        steps.add(step)
        return step
    }

    fun run(): AppAutomator {
        for (step in steps) {
            if (!step.run()) {
                this.message = "run step ${step.name} failed: ${step.message}"
                return this
            }
        }
        this.message = "run ${steps.size} steps successfully"
        allStepsSucceed = true
        return this
    }

    fun close() {
        privateData.clear()
    }

    protected fun finalize() {
        close()
    }

    fun newOpeningAppStep(packageName: String): AutomationStep {
        val step = AutomationStep("step_open_app_$packageName", this)
        step.retry(0).postActionDelay(0).action {
            quitApp(srv, packageName)
            openApp(srv, srv.applicationContext, packageName)
        }.expect { tree, step ->
            tree.packageName == packageName
        }
        steps.add(step)
        return step
    }
}