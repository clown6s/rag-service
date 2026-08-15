package com.zzp.rag.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 异步任务查询（长任务框架的统一入口）：
 * 评测运行、未来的图谱重建/批量摄入等长操作都走 /api/rag/admin/tasks 轮询。
 */
@RestController
@RequestMapping("/api/rag/admin/tasks")
public class TaskController {

    private final AsyncTaskService asyncTaskService;

    public TaskController(AsyncTaskService asyncTaskService) {
        this.asyncTaskService = asyncTaskService;
    }

    @GetMapping
    public ApiResponse<List<AsyncTaskService.TaskView>> list() {
        return ApiResponse.ok(asyncTaskService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<AsyncTaskService.TaskView> get(@PathVariable String id) {
        AsyncTaskService.TaskView task = asyncTaskService.get(id);
        return task != null ? ApiResponse.ok(task) : ApiResponse.fail("任务不存在");
    }
}
