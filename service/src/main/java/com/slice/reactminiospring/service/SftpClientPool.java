package com.slice.reactminiospring.service;

import com.jcraft.jsch.*;
import com.slice.reactminiospring.entity.SftpServerConfigs;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SftpClientPool {

    private final Map<Long, ChannelSftp> pool = new ConcurrentHashMap<>();

    public synchronized ChannelSftp get(SftpServerConfigs cfg) throws Exception {
        if (pool.containsKey(cfg.getId()) && pool.get(cfg.getId()).isConnected()) {
            return pool.get(cfg.getId());
        }

        JSch jsch = new JSch();
        Session session = jsch.getSession(cfg.getUsername(), cfg.getHost(), cfg.getPort());
        session.setPassword(cfg.getPassword());
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(10000);

        Channel channel = session.openChannel("sftp");
        channel.connect(5000);

        ChannelSftp sftp = (ChannelSftp) channel;
        pool.put(cfg.getId(), sftp);
        return sftp;
    }

    public void close(Long id) {
        ChannelSftp sftp = pool.remove(id);
        if (sftp != null) {
            sftp.disconnect();
            try {
                sftp.getSession().disconnect();
            } catch (JSchException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
