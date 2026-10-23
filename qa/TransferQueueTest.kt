import com.netkit.local.transfer.*
import java.io.File

fun main(args:Array<String>) {
    val file=File(args[0],"queue.properties")
    val queue=TransferQueue(FileTransferJournal(file))
    val task=queue.add("content://test/source","peer/file.bin",100)
    check(queue.next()?.id==task.id)
    check(queue.progress(task.id,40)?.completedBytes==40L)
    check(queue.pause(task.id)?.state==TransferState.PAUSED)
    val restored=TransferQueue(FileTransferJournal(file))
    check(restored.snapshot().single().completedBytes==40L)
    check(restored.resume(task.id)?.state==TransferState.QUEUED)
    check(restored.next()?.attempts==2)
    check(restored.progress(task.id,100)?.completedBytes==100L)
    check(restored.finish(task.id)?.state==TransferState.COMPLETED)
    check(TransferQueue(FileTransferJournal(file)).snapshot().single().state==TransferState.COMPLETED)
    println("PASS: persistent transfer queue, progress, pause/resume/retry, completion")
}
