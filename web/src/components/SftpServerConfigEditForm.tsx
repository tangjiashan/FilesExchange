import React, { useEffect } from 'react';
import { Modal, Form, Input, InputNumber, Switch, message } from 'antd';
import { createConfig, updateConfig } from '../services/apis';
import { SftpServerConfig } from './SftpServerConfigPage';

interface Props {
  open: boolean;
  initialValues?: SftpServerConfig | null;
  onClose: () => void;
  onSuccess: () => void;
}

const SftpServerConfigEditForm: React.FC<Props> = ({ open, initialValues, onClose, onSuccess }) => {
  const [form] = Form.useForm();

  useEffect(() => {
    if (initialValues) form.setFieldsValue(initialValues);
    else form.resetFields();
  }, [initialValues]);

  const handleSubmit = async () => {
    const values = await form.validateFields();
    const res = initialValues
      ? await updateConfig(initialValues.id!, values)
      : await createConfig(values);

    if (res.code === 200) {
      message.success('保存成功');
      onSuccess();
    }
  };

  return (
    <Modal
      title={initialValues ? '编辑配置' : '新增配置'}
      open={open}
      onCancel={onClose}
      onOk={handleSubmit}
    >
      <Form form={form} layout="vertical">
        {/* <Form.Item label="站点ID" name="stationId" rules={[{ required: true }]}>
          <Input />
        </Form.Item> */}
        <Form.Item label="名称" name="name" rules={[{ required: true }]}>
          <Input />
        </Form.Item>
        <Form.Item label="主机" name="host" rules={[{ required: true }]}>
          <Input />
        </Form.Item>
        <Form.Item label="端口" name="port" initialValue={22}>
          <InputNumber min={1} max={65535} />
        </Form.Item>
        <Form.Item label="用户名" name="username">
          <Input />
        </Form.Item>
        <Form.Item label="密码" name="password">
          <Input.Password />
        </Form.Item>
        <Form.Item label="源目录" name="sourceDir">
          <Input />
        </Form.Item>
        <Form.Item label="目标目录" name="targetDir">
          <Input />
        </Form.Item>
        <Form.Item label="重试次数" name="retryCount" initialValue={3}>
          <InputNumber />
        </Form.Item>
        <Form.Item label="重试延时(ms)" name="retryDelay" initialValue={3000}>
          <InputNumber />
        </Form.Item>
        <Form.Item label="启用" name="enabled" valuePropName="checked">
          <Switch />
        </Form.Item>
      </Form>
    </Modal>
  );
};

export default SftpServerConfigEditForm;
