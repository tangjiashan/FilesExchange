package com.slice.reactminiospring.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slice.reactminiospring.common.R;
import com.slice.reactminiospring.entity.SftpServerConfigs;
import com.slice.reactminiospring.mapper.SftpServerMapper;
import com.slice.reactminiospring.service.ISftpServerConfigService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
@Slf4j
@Service
public class SftpServerConfigsServiceImpl extends ServiceImpl<SftpServerMapper, SftpServerConfigs> implements ISftpServerConfigService {

    @Resource
    private SftpServerMapper sftpServerMapper;

    @Override
    public List<SftpServerConfigs> getSftpServerConfigList() {
        return sftpServerMapper.selectList(new QueryWrapper<SftpServerConfigs>().eq("enabled", true));
    }
}
