# 库到库 ETL 任务统一调度设计

## 说明

当前 `EtlTask` 面向一对一的 ETL 处理过程，可以提交到线程池中执行。
库到库场景通常需要为多个表创建多个 `EtlTask`。本文说明如何在不增加任务状态持久化和断点续传的前提下，对这些任务进行统一调度。

本文中的“暂停”有明确限制：

```text
暂停 = 终止正在运行的任务
继续 = 重新创建任务，并从头执行
```

因此，任务进度不会保留，服务重启后也无法恢复内存中的调度信息。

## 设计目标

- 用户以库级别的 Job 提交和管理 ETL。
- 一个 Job 可以包含多个表级 `EtlTask`。
- 通过统一队列控制任务排队顺序。
- 通过线程池并发上限控制资源占用。
- 支持全部暂停、部分暂停、继续和删除任务。
- 不让用户直接管理线程。

## 核心对象

```text
EtlJob
 ├── TaskDefinition(table_1)
 ├── TaskDefinition(table_2)
 └── TaskDefinition(table_3)

EtlJobManager
 ├── 管理 Job
 ├── 管理等待队列
 ├── 管理运行中的任务
 └── 管理线程池
```

### EtlJob

表示一次库到库的提交请求，保存源库、目标库以及该 Job 下的表任务定义。

```pseudo
class EtlJob:
    id
    sourceDatabase
    targetDatabase
    taskDefinitions
    state              // CREATED、RUNNING、PAUSED、FINISHED、CANCELLED
    pausedTaskIds
    deletedTaskIds
```

这些信息仅存在于当前进程内，不代表数据库持久化状态。

### TaskDefinition

表示如何创建一个表级任务，不直接持有线程。

```pseudo
class TaskDefinition:
    taskId
    sourceTable
    targetTable
```

### RunningTask

表示当前正在执行的任务实例及其取消控制对象。

```pseudo
class RunningTask:
    taskId
    future
    cancelToken
```

## 提交库级任务

提交时先发现库中的表，为每张表生成任务定义，再全部放入统一队列。不要为每张表永久创建一个线程。

```pseudo
function submitDatabaseJob(sourceDb, targetDb):
    job = new EtlJob(sourceDb, targetDb)
    job.id = generateId()
    job.state = RUNNING

    for table in discoverTables(sourceDb):
        definition = new TaskDefinition(table, table)
        job.taskDefinitions.add(definition)
        waitingQueue.add(job.id, definition)

    jobs[job.id] = job
    schedule()

    return job.id
```

## 统一调度

调度器只在运行数小于并发上限时取出队列任务，并在真正启动前再次检查 Job、暂停和删除标记。

```pseudo
function schedule():
    while runningTasks.size < maxConcurrentTasks:
        item = waitingQueue.poll()
        if item == null:
            break

        job = jobs[item.jobId]
        if job == null or job.state != RUNNING:
            continue
        if item.taskId in job.pausedTaskIds:
            continue
        if item.taskId in job.deletedTaskIds:
            continue

        startTask(job, item)
```

启动任务时重新创建 `EtlTask`，任务结束后释放运行槽位并触发下一轮调度。

```pseudo
function startTask(job, definition):
    token = new CancelToken()

    future = threadPool.submit(() =>:
        try:
            task = new EtlTask(
                source = job.sourceDatabase,
                target = job.targetDatabase,
                table = definition.sourceTable
            )
            task.run(token)
            onTaskFinished(job.id, definition.taskId)
        catch CancelledException:
            onTaskStopped(job.id, definition.taskId)
        catch Exception e:
            onTaskFailed(job.id, definition.taskId, e)
        finally:
            runningTasks.remove(definition.taskId)
            schedule()
    )

    runningTasks[definition.taskId] = new RunningTask(
        definition.taskId, future, token
    )
```

## 取消正在运行的任务

优先使用协作式取消。`EtlTask` 在批次之间检查取消标记，发现取消请求后自行结束。

```pseudo
class CancelToken:
    cancelled = false

    function cancel():
        cancelled = true

    function isCancelled():
        return cancelled
```

```pseudo
function EtlTask.run(token):
    for batch in readSourceData():
        if token.isCancelled():
            throw CancelledException()
        processBatch(batch)
```

如果当前 `EtlTask` 无法检查取消标记，只能调用 `future.cancel(true)`。这种方式对阻塞 IO、数据库驱动调用或不可中断代码不一定能立即生效，因此需要明确告知调用方：取消是请求，不保证瞬时终止。

## 全部暂停

全部暂停包含两步：阻止等待任务启动，终止当前正在运行的任务。

```pseudo
function pauseJob(jobId):
    job = jobs[jobId]
    job.state = PAUSED

    for definition in job.taskDefinitions:
        job.pausedTaskIds.add(definition.taskId)

        runningTask = runningTasks[definition.taskId]
        if runningTask != null:
            runningTask.cancelToken.cancel()
            runningTask.future.cancel(true)

    schedule()
```

已运行任务不会保存进度；已排队任务不会被启动。

## 部分暂停

部分暂停只修改指定任务，并取消其中正在运行的任务。

```pseudo
function pauseTasks(jobId, taskIds):
    job = jobs[jobId]

    for taskId in taskIds:
        job.pausedTaskIds.add(taskId)

        runningTask = runningTasks[taskId]
        if runningTask != null:
            runningTask.cancelToken.cancel()
            runningTask.future.cancel(true)

    schedule()
```

队列中已经存在的任务不要求立即物理移除，调度器取出任务时再次检查 `pausedTaskIds` 即可跳过。

## 继续运行

继续的实际行为是将任务重新加入等待队列。调度器启动任务时会重新创建 `EtlTask`，所以任务从头开始执行。

```pseudo
function resumeTasks(jobId, taskIds):
    job = jobs[jobId]

    for taskId in taskIds:
        if taskId in job.deletedTaskIds:
            continue

        job.pausedTaskIds.remove(taskId)
        definition = findTaskDefinition(job, taskId)
        if definition != null:
            waitingQueue.add(jobId, definition)

    job.state = RUNNING
    schedule()
```

继续整个 Job 只是对 Job 下所有未删除任务执行同样的重新入队操作。

## 删除单个任务

建议采用内存软删除，不直接从任务定义列表中移除。这样可以避免队列中已经存在同一任务时，删除操作与调度操作发生竞态。

```pseudo
function deleteTask(jobId, taskId):
    job = jobs[jobId]
    job.deletedTaskIds.add(taskId)
    job.pausedTaskIds.remove(taskId)

    runningTask = runningTasks[taskId]
    if runningTask != null:
        runningTask.cancelToken.cancel()
        runningTask.future.cancel(true)

    schedule()
```

调度器必须在启动前检查删除标记：

```pseudo
if taskId in job.deletedTaskIds:
    skip()
```

## 对外接口建议

```pseudo
submitDatabaseJob(sourceDb, targetDb)

pauseJob(jobId)
resumeJob(jobId)

pauseTasks(jobId, taskIds)
resumeTasks(jobId, taskIds)

deleteTask(jobId, taskId)
cancelJob(jobId)

getJobOverview(jobId)
```

## 关键限制和风险

### 任务状态只存在于内存

进程重启后，Job、队列、暂停标记和删除标记全部丢失。当前方案不支持服务重启恢复，也不支持跨进程调度。

### 继续会重复处理数据

任务从头执行，目标端必须能够承受重复写入。可根据实际业务选择以下策略：

- 目标表先清空再全量写入。
- 通过主键冲突覆盖或更新。
- 写入临时表后再替换目标表。
- 为每次提交增加批次标识，并由目标端保证幂等。

### 取消不一定立即生效

如果任务正在执行不可中断的数据库操作，取消请求只能在该操作返回后生效。调度器需要区分“已请求取消”和“已确认结束”，不能在请求发出后立即把该任务当作已释放线程。

### Job 状态应由运行集合推导

当前没有持久化状态时，不需要为每个任务维护复杂状态机。可以根据任务定义、暂停集合、删除集合和运行集合计算概览：

```pseudo
function getJobOverview(jobId):
    return {
        total: taskDefinitions.size,
        running: count(runningTasks),
        queued: count(waitingQueue for jobId),
        paused: pausedTaskIds.size,
        deleted: deletedTaskIds.size,
        finished: finishedTaskIds.size,
        failed: failedTaskIds.size
    }
```

## 推荐边界

```text
用户操作 Job
调度器分配 Task
线程池负责执行
Task 负责检查取消信号
内存集合负责记录暂停和删除意图
```

后续如果需要断点续传、服务重启恢复或跨进程调度，再单独引入持久化任务状态和 checkpoint；当前不应把这些能力假设为已有能力。
