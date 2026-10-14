package app.harvester.companion

import java.util.concurrent.CopyOnWriteArrayList

object MenuCommandStore {

    data class Command(val id: String, val name: String, val accessKey: String)

    private val list = CopyOnWriteArrayList<Command>()

    fun register(id: String, name: String, accessKey: String) {
        list.removeAll { it.id == id }
        list.add(Command(id, name, accessKey))
    }

    fun unregister(id: String) {
        list.removeAll { it.id == id }
    }

    fun clear() { list.clear() }

    fun all(): List<Command> = list.toList()

    fun isEmpty(): Boolean = list.isEmpty()
}
