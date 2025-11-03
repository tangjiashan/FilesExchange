package com.slice.reactminiospring.controller;

import com.slice.reactminiospring.common.R;
import com.slice.reactminiospring.entity.SftpServerConfigs;

import com.slice.reactminiospring.service.ISftpServerConfigService;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sftp/configs")
@RequiredArgsConstructor
public class SftpServerConfigsController {

    @Resource
    private ISftpServerConfigService service;

    @GetMapping
    public R<List<SftpServerConfigs>> list() {
        return R.ok(service.list());
    }

    @PostMapping
    public R<?> create(@RequestBody SftpServerConfigs cfg) {
        service.save(cfg);
        return R.ok();
    }

    @PutMapping("/{id}")
    public R<?> update(@PathVariable Long id, @RequestBody SftpServerConfigs cfg) {
        cfg.setId(id);
        service.updateById(cfg);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<?> delete(@PathVariable Long id) {
        service.removeById(id);
        return R.ok();
    }
}
