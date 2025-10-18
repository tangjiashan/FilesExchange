import { useState } from 'react'
import { Button, Flex, message, Space, Typography } from 'antd'
import FileTable from './components/FileTable'
import UploadModal from './components/UploadModal'
import useGetState from './hooks/useGetState'
import './App.css'

const { Text } = Typography

function App() {
  const [open, setOpen] = useState(false)
  const [count, setCount, getCount] = useGetState(0)

  return (
    <>
      <Space>
        <Button
          onClick={() => {
            const newCount = count + 1
            setCount(newCount)
            message.info(`count: ${count}----getCount: ${getCount()}`)
          }}
        >
          测试 useGetState
        </Button>

        <Text type="secondary">
          由于数据比较复杂, react 的 useState 异步情况下很难处理数据, 去除强依赖 ahooks 的情况下封装
          <Text code>useGetState</Text>
        </Text>
      </Space>
      <Flex style={{ marginBottom: 20 }}>
        <Button type="primary" onClick={() => setOpen(true)}>
          上传文件
        </Button>
      </Flex>

      <FileTable />

      <UploadModal open={open} onCancel={() => setOpen(false)} />
    </>
  )
}

export default App
