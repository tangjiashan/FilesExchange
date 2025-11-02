package com.slice.reactminiospring.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slice.reactminiospring.entity.SftpServerConfigs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface SftpServerMapper extends BaseMapper<SftpServerConfigs> {

    // 注意：如果你想使用 MyBatis-Plus 的方式，可以在 Service 层这样调用：
    // sftpServerMapper.selectList(new QueryWrapper<SftpServerConfig>().eq("enabled", true))
}
