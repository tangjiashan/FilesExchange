import { memo, useEffect, useRef } from 'react'
import { Button, Progress, Table } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { DownloadOutlined, PauseCircleOutlined, PlayCircleOutlined } from '@ant-design/icons'
import { CHUNK_SIZE } from '../constants'
import { convertFileSizeUnit, downloadFileByBlob } from '../util/fileUtil'
import { chunkDownloadFile, fetchFileList } from '../services/apis'
import type { FilesType } from '../services/apis/typing'
import useGetState from '@/hooks/useGetState'

type DownloadStatus = {
  progress?: number
  status?: 'downloading' | 'pause' | 'error'
}

type FileDataType = FilesType & DownloadStatus

const FileTable: React.FC = memo(() => {
  const blobRef = useRef(new Map<number, BlobPart[]>()) // 必须要 ref 缓存，否则 react 的重渲染机制会导致数据重置
  // const state = useReactive<{ dataSource: FileDataType[] }>({
  //   dataSource: [],
  // })
  const [dataSource, setDataSource, getDataSource] = useGetState<FileDataType[]>([])

  const columns: ColumnsType<FileDataType> = [
    {
      title: '主键id',
      dataIndex: 'id',
      rowScope: 'row',
      width: 80,
    },
    {
      title: '原文件名',
      dataIndex: 'originFileName',
      ellipsis: true,
    },
    {
      title: 'object',
      dataIndex: 'object',
      ellipsis: true,
    },
    {
      title: '文件大小',
      dataIndex: 'size',
      width: 100,
      render: (val) => convertFileSizeUnit(val),
    },
    {
      title: '下载进度',
      dataIndex: 'progress',
      render: (val) =>
      val !== undefined ? <Progress percent={val} size="small" /> : null,
    },
    {
      title: '操作',
      dataIndex: 'status',
      width: 120,
      render: (val, record, index) => {
        if (val === undefined || val === 'error') {
          return (
            <Button
              type="primary"
              icon={<DownloadOutlined />}
              onClick={() => downloadFile(record, index)}
            />
          )
        } else {
          return (
            <>
              {val == 'downloading' ? (
                // 暂停
                <Button
                  type="primary"
                  icon={<PauseCircleOutlined />}
                  onClick={() => puaseDownload(index)}
                />
              ) : (
                // 继续下载
                <Button
                  type="primary"
                  ghost
                  icon={<PlayCircleOutlined />}
                  onClick={() => downloadFile(record, index)}
                />
              )}
            </>
          )
        }
      },
    },
  ]

  useEffect(() => {
    getFileTableList()
  }, [])

  const getFileTableList = async () => {
    const { code, data } = await fetchFileList()
    if (code === 200) setDataSource(data)
  }

  // 分片下载文件
  const downloadFile = async (record: FileDataType, index: number) => {
    const _dataSource = [...getDataSource()] // 每次赋值前浅拷贝最新值，防止数据的异步获取和地址不变造成的影响
    // state.dataSource[index].status = 'downloading'
    _dataSource[index].status = 'downloading'
    setDataSource(_dataSource)
    const totalChunks = Math.ceil(record.size / CHUNK_SIZE) // 请求次数，可自定义调整分片大小，这里默认了上传分片大小
    // 如果是暂停，根据已下载的，找到断点，偏移长度进行下载
    const offset = blobRef.current.get(record.id)?.length || 0

    for (let i = offset + 1; i <= totalChunks; i++) {
      if (getDataSource()[index].status !== 'downloading') return

      const start = CHUNK_SIZE * (i - 1)
      let end = Math.min(CHUNK_SIZE * i - 1, record.size)

    try {
      const res = await chunkDownloadFile(record.id, `bytes=${start}-${end}`)
      const currentDataBlob = blobRef.current.get(record.id) || []
      blobRef.current.set(record.id, [...currentDataBlob, res as unknown as BlobPart])

      const _dataSource = [...getDataSource()]
      const percent = Math.min(100, Math.ceil((i / totalChunks) * 100))
      _dataSource[index].progress = percent
      setDataSource(_dataSource)

      //  给 React 时间渲染
      await new Promise((resolve) => setTimeout(resolve, 30))
    } catch (error) {
      const _dataSource = [...getDataSource()]
      _dataSource[index].status = 'error'
      setDataSource(_dataSource)
      return
    }
  }
    const _dataSource1 = [...getDataSource()]
    _dataSource1[index].status = undefined // 重置状态
    _dataSource1[index].progress = undefined // 重置进度条
    setDataSource(_dataSource1)

    const blob = new Blob(blobRef.current.get(record.id))
    downloadFileByBlob(blob, record.originFileName)
  }

  // 暂停下载
  const puaseDownload = (index: number) => {
    const _dataSource = [...getDataSource()]
    _dataSource[index].status = 'pause'
    setDataSource(_dataSource)
  }

  return <Table rowKey="id" columns={columns} dataSource={dataSource} />
})

export default FileTable
