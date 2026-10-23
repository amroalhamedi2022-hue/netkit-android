package com.netkit.local.transfer

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import java.util.UUID

/** Persistent intentions: protocol adapter must still verify remote offsets and hashes. */
enum class TransferState { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }
data class TransferTask(
    val id: String, val source: String, val destination: String, val size: Long,
    val completedBytes: Long = 0, val state: TransferState = TransferState.QUEUED,
    val attempts: Int = 0, val error: String = ""
)
interface TransferJournal {
    fun load(): List<TransferTask>
    fun save(tasks: List<TransferTask>)
}
class TransferQueue(private val journal: TransferJournal) {
    private val lock = Any()
    private val tasks = journal.load().map {
        if (it.state == TransferState.RUNNING)
            it.copy(state = TransferState.PAUSED, error = "Interrupted: recheck remote offset")
        else it
    }.toMutableList()
    init { journal.save(tasks) }
    fun snapshot(): List<TransferTask> = synchronized(lock) { tasks.toList() }
    fun add(source: String, destination: String, size: Long): TransferTask = synchronized(lock) {
        require(source.isNotBlank() && destination.isNotBlank())
        require(size >= 0 && tasks.size < 1000)
        val task = TransferTask(UUID.randomUUID().toString(),source,destination,size)
        tasks.add(task); journal.save(tasks); task
    }
    fun next(): TransferTask? = synchronized(lock) {
        val index = tasks.indexOfFirst { it.state == TransferState.QUEUED }
        if (index < 0) null else change(index) { it.copy(state=TransferState.RUNNING, attempts=it.attempts+1) }
    }
    fun progress(id:String, bytes:Long):TransferTask?=synchronized(lock){
        val index=tasks.indexOfFirst{it.id==id && it.state==TransferState.RUNNING}
        if(index<0) null else change(index){it.copy(completedBytes=bytes.coerceIn(it.completedBytes,it.size))}
    }
    fun pause(id:String)=transition(id,setOf(TransferState.RUNNING,TransferState.QUEUED),TransferState.PAUSED)
    fun resume(id:String)=transition(id,setOf(TransferState.PAUSED,TransferState.FAILED),TransferState.QUEUED)
    fun cancel(id:String)=transition(id,setOf(TransferState.RUNNING,TransferState.PAUSED,TransferState.QUEUED,TransferState.FAILED),TransferState.CANCELLED)
    fun finish(id:String)=synchronized(lock){
        val i=tasks.indexOfFirst{it.id==id && it.state==TransferState.RUNNING}
        if(i<0)null else change(i){it.copy(state=TransferState.COMPLETED,completedBytes=it.size,error="")}
    }
    fun fail(id:String,message:String)=synchronized(lock){
        val i=tasks.indexOfFirst{it.id==id && it.state==TransferState.RUNNING}
        if(i<0)null else change(i){it.copy(state=TransferState.FAILED,error=message.take(200))}
    }
    private fun transition(id:String,from:Set<TransferState>,to:TransferState):TransferTask?=synchronized(lock){
        val index=tasks.indexOfFirst{it.id==id && it.state in from}
        if(index<0)null else change(index){it.copy(state=to)}
    }
    private fun change(i:Int, update:(TransferTask)->TransferTask):TransferTask {
        val before=tasks[i]; val after=update(before); tasks[i]=after
        try {journal.save(tasks)} catch(e:Exception){tasks[i]=before;throw e}
        return after
    }
}
/** App-private file journal, recoverable on process recreation. */
class FileTransferJournal(private val file:File): TransferJournal {
    override fun load():List<TransferTask> = runCatching {
        if(!file.exists())return emptyList()
        val data=Properties(); FileInputStream(file).use(data::load)
        val count=(data.getProperty("count")?.toIntOrNull()?:0).coerceIn(0,1000)
        (0 until count).mapNotNull { i ->
            runCatching {
                val b="$i."
                TransferTask(data.getProperty(b+"id"),data.getProperty(b+"source"),
                    data.getProperty(b+"dest"),data.getProperty(b+"size").toLong(),
                    data.getProperty(b+"done").toLong(),
                    TransferState.valueOf(data.getProperty(b+"state")),
                    data.getProperty(b+"attempts").toInt(),data.getProperty(b+"error",""))
            }.getOrNull()
        }
    }.getOrDefault(emptyList())
    override fun save(tasks:List<TransferTask>) {
        val properties=Properties();properties.setProperty("count",tasks.size.toString())
        tasks.forEachIndexed{i,t ->
            val b="$i."
            properties.setProperty(b+"id",t.id);properties.setProperty(b+"source",t.source)
            properties.setProperty(b+"dest",t.destination);properties.setProperty(b+"size",t.size.toString())
            properties.setProperty(b+"done",t.completedBytes.toString());properties.setProperty(b+"state",t.state.name)
            properties.setProperty(b+"attempts",t.attempts.toString());properties.setProperty(b+"error",t.error)
        }
        file.parentFile?.mkdirs()
        val staging=File(file.parentFile,file.name+".new")
        FileOutputStream(staging).use { out -> properties.store(out,"NetKit queue");out.fd.sync() }
        if(!staging.renameTo(file)){
            if(file.exists()&&!file.delete())error("Cannot rotate transfer journal")
            check(staging.renameTo(file)){"Journal rename failed"}
        }
    }
}
