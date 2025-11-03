import { useState } from 'react'
import { Button, Flex, message, Space, Typography } from 'antd'
import FileTable from './components/FileTable'
import UploadModal from './components/UploadModal'
import SftpServerConfigPage from './components/SftpServerConfigPage'
import useGetState from './hooks/useGetState'
import './App.css'

const { Text } = Typography

function App() {
  const [open, setOpen] = useState(false)
  const [count, setCount, getCount] = useGetState(0)
  const [showSftpConfig, setShowSftpConfig] = useState(false) // ✅ 新增：是否显示SFTP配置界面

  // ✅ 点击按钮后切换到 SFTP Server Config 页面
  const handleGoToSftpConfig = () => {
    setShowSftpConfig(true)
  }

  // ✅ 在配置界面中可以返回主界面
  const handleBackToMain = () => {
    setShowSftpConfig(false)
  }

  // ✅ 判断是否处于 SFTP 配置界面
  if (showSftpConfig) {
    return (
      <div style={{ padding: 20 }}>
        <Button type="link" onClick={handleBackToMain}>
          ← 返回文件列表
        </Button>
        <SftpServerConfigPage />
      </div>
    )
  }

  // ✅ 主页面：文件上传 + 表格
  return (
    <>
      <Space style={{ marginBottom: 20 }}>
        <Button type="primary" onClick={() => setOpen(true)}>
          上传文件
        </Button>

          <Button type="default"
          onClick={() => {
            handleGoToSftpConfig() // ✅ 点击后切换到配置界面
          }}
        >
          SFTP配置
        </Button>
      </Space>

      <FileTable />

      <UploadModal open={open} onCancel={() => setOpen(false)} />
    </>
  )
}

export default App
