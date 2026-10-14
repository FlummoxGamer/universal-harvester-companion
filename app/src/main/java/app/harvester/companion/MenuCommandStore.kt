package app.harvester.companion

import java.util.concurrent.CopyOnWriteArrayList

object MenuCommandStore {

    data class Command(val script: String, val id: String, val name: String, val accessKey: String)

    private val list = CopyOnWriteArrayList<Command>()

    @Volatile var listener: (() -> Unit)? = null

    fun register(script: String, id: String, name: String, accessKey: String) {
        list.removeAll { it.script == script && it.id == id }
        list.add(Command(script, id, name, accessKey))
        listener?.invoke()
    }

    fun unregister(script: String, id: String) {
        list.removeAll { it.script == script && it.id == id }
        listener?.invoke()
    }

    fun clearScript(script: String) {
        list.removeAll { it.script == script }
        listener?.invoke()
    }

    fun clear() { list.clear(); listener?.invoke() }

    fun all(): List<Command> = list.toList()

    fun forScript(script: String): List<Command> = list.filter { it.script == script }

    fun isEmpty(): Boolean = list.isEmpty()
}
