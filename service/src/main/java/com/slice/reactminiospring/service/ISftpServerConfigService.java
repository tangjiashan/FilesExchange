package com.slice.reactminiospring.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.slice.reactminiospring.entity.SftpServerConfigs;

import java.util.List;

public interface ISftpServerConfigService extends IService<SftpServerConfigs> {
    List<SftpServerConfigs> getSftpServerConfigList();
}
