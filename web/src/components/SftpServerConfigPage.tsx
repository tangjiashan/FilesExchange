import React, { useEffect, useState } from 'react';
import { Button, Table, Switch, Space, Modal, message } from 'antd';
import { getConfigs, deleteConfig } from '../services/apis';
import EditForm from './SftpServerConfigEditForm';

export interface SftpServerConfig {
  id?: number;
  stationId: string;
  name: string;
  host: string;
  port: number;
  username: string;
  password: string;
  sourceDir: string;
  targetDir: string;
  retryCount: number;
  retryDelay: number;
  enabled: boolean;
}

const SftpServerConfigPage: React.FC = () => {
  const [configs, setConfigs] = useState<SftpServerConfig[]>([]);
  const [loading, setLoading] = useState(false);
  const [editing, setEditing] = useState<SftpServerConfig | null>(null);
  const [modalVisible, setModalVisible] = useState(false);

  const fetchData = async () => {
    setLoading(true);
    try {
      const res = await getConfigs();
      if (res.code === 200) {
        setConfigs(res.data as SftpServerConfig[]);
      } else {
        message.error((res as any).message || '获取配置失败');
      }
    } catch (error) {
      message.error('获取配置失败');
      console.error(error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, []);

  const handleDelete = async (id: number) => {
    Modal.confirm({
      title: '确认删除该配置？',
      onOk: async () => {
        try {
          const res = await deleteConfig(id);
          if (res.code === 200) {
            message.success('删除成功');
            fetchData();
          } else {
            message.error((res as any).message || '删除失败');
          }
        } catch (error) {
          message.error('删除失败');
          console.error(error);
        }
      },
    });
  };

  const columns = [
    { title: '场站', dataIndex: 'name' },
    { title: '主机地址', dataIndex: 'host' },
    { title: '端口', dataIndex: 'port' },
    { title: '用户名', dataIndex: 'username' },
    { title: '源目录', dataIndex: 'sourceDir' },
    { title: '目标目录', dataIndex: 'targetDir' },
    { title: '重试次数', dataIndex: 'retryCount' },
    { title: '重试延时', dataIndex: 'retryDelay' },
    {
      title: '启用',
      dataIndex: 'enabled',
      render: (val: boolean) => <Switch checked={val} disabled />,
    },
    {
      title: '操作',
      render: (_: any, record: SftpServerConfig) => (
        <Space>
          <Button onClick={() => { setEditing(record); setModalVisible(true); }}>编辑</Button>
          <Button danger onClick={() => handleDelete(record.id!)}>删除</Button>
        <Button type="primary" onClick={() => { setEditing(null); setModalVisible(true); }}>
          新增
        </Button>
        </Space>
      ),
    },
  ];

  return (
    <div>
      {/* <Space style={{ marginBottom: 16 }}>
        <Button type="primary" onClick={() => { setEditing(null); setModalVisible(true); }}>
          新增配置
        </Button>
        <Button onClick={fetchData}>刷新</Button>
      </Space> */}
      <Table rowKey="id" loading={loading} columns={columns} dataSource={configs} />
      <EditForm
        open={modalVisible}
        onClose={() => setModalVisible(false)}
        onSuccess={() => { setModalVisible(false); fetchData(); }}
        initialValues={editing}
      />
    </div>
  );
};

export default SftpServerConfigPage;
