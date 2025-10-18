import { memo } from 'react'
import { Button, Card, List, Modal, Progress, Space, Tag, Upload } from 'antd'
import type { RcFile, UploadChangeParam } from 'antd/es/upload'
import { CloudUploadOutlined, UploadOutlined } from '@ant-design/icons'
import axios from 'axios'
import pLimit from 'p-limit'
import { CHUNK_SIZE } from '../constants'
// import createChunkFileAndMd5 from '../util/createChunkFileAndMd5'
import { convertFileSizeUnit } from '../util/fileUtil'
import { checkFileByMd5, initMultPartFile, mergeFileByMd5 } from '../services/apis'
import { HttpCodeUploadEnum } from '../services'
import type { UploadFileInfoType } from '../services/apis/typing'
import cutFile from '../core/cutFile'
import { MerkleTree } from '../core/MerkleTree'
import useGetState from '@/hooks/useGetState'

const limit = pLimit(3)

type UploadModalProps = {
  open: boolean
  onCancel: () => void
}

/** 分片上传时的 file 和上传地址 url */
type ChunkFileUrlType = {
  url: string
  file: Blob
}

/** 表格数据类型 */
type FileTableDataType = {
  uid: string
  name: string
  size: number
  unitSize: string
  md5: string
  md5Progress: number
  progress: number
  file: File
  chunkCount: number
  /** 当前文件分片集合 */
  chunkFileList: Blob[]
  /** 已上传的文件大小总和（计算进度条） */
  uploadedSize: number
  /** 计算MD5中（加载中） | 等待上传 | 上传中  | 上传成功 | 上传失败 */
  status: 'preparation' | 'preupload' | 'uploading' | 'success' | 'error'
}

//  文件上传过程中的多种状态
const tagMap = {
  preparation: { color: 'gold', text: 'MD5计算中' },
  preupload: { color: 'purple', text: '等待上传' },
  uploading: { color: 'processing', text: '上传中' },
  success: { color: 'success', text: '上传成功' },
  error: { color: 'error', text: '上传失败' },
}

const UploadModal: React.FC<UploadModalProps> = memo((props) => {
  const { open, onCancel } = props
  const [dataSource, setDataSource, getDataSource] = useGetState<FileTableDataType[]>([])

  // 选择文件并计算 md5
  const selectFile = async (info: UploadChangeParam) => {
    const file = info.file
    const chunkCount = Math.ceil((file.size ?? 0) / CHUNK_SIZE)
    // 展示给 table的数据，部分参数用于初始化
    const dataItem: FileTableDataType = {
      uid: file.uid,
      name: file.name,
      size: file.size ?? 0,
      unitSize: convertFileSizeUnit(file?.size),
      md5: '',
      md5Progress: 0,
      progress: 0,
      chunkCount,
      file: file as RcFile,
      status: 'preparation',
      chunkFileList: [],
      uploadedSize: 0,
    }
    const _dataSource = [...getDataSource()]
    _dataSource.push(dataItem)
    setDataSource(_dataSource)

    const i = getDataSource().findIndex((item) => item.uid == dataItem.uid)
    // 同步计算分片文件和 md5，实时更新计算进度 注释的方法可以使用，只是 hash 计算速度较慢
    // const { md5, chunkFileList } = await createChunkFileAndMd5(
    //   file as RcFile,
    //   chunkCount,
    //   (progress) => {
    //     state.dataSource[i].md5Progress = progress
    //   },
    // )

    // 采用多线程计算和默克尔树计算树根
    const chunks = await cutFile(file as RcFile)
    const merkleTree = new MerkleTree(chunks.map((chunk) => chunk.hash))
    const md5 = merkleTree.getRootHash()
    const chunkFileList = chunks.map((chunk) => chunk.blob)
    // console.log(md5, chunkFileList)

    // 更新数据和状态
    const _dataSource1 = [...getDataSource()]
    _dataSource1[i] = {
      ..._dataSource1[i],
      md5,
      chunkFileList,
      status: 'preupload',
    }
    setDataSource(_dataSource1)
  }

  // 查询文件状态并上传
  const onUpload = async () => {
    for (let i = 0; i < dataSource.length; i++) {
      // md5 未计算完成和正在上传的跳过（重复点击的情况）
      if (!dataSource[i].md5 || dataSource[i].status == 'uploading') continue

      await uploadFile(i, dataSource[i])
    }
  }

  // 上传处理方法
  const uploadFile = async (index: number, item: FileTableDataType) => {
    const { code, data } = await checkFileByMd5(item.md5)
    const _dataSource = [...getDataSource()]
    _dataSource[index].status = 'uploading'
    setDataSource(_dataSource)

    if (code === HttpCodeUploadEnum.SUCCESS) {
      //  上传成功
      const _dataSource = [...getDataSource()]
      _dataSource[index].progress = 100
      _dataSource[index].status = 'success'
      setDataSource(_dataSource)
      return
    } else if (code === HttpCodeUploadEnum.FAIL) {
      //  上传失败
      const _dataSource = [...getDataSource()]
      _dataSource[index].status = 'error'
      setDataSource(_dataSource)
      return
    } /*  else if (code === HttpCodeUploadEnum.UPLOADING) {
        // 上传中，返回已上传的文件数据和分片列表
      } else {
        // 未上传
      } */

    // 返回需要上传分片和对应地址
    const needUploadFile = await initSliceFile(item, data)
    console.log('需要上传的文件', needUploadFile)
    const totalSize = needUploadFile.reduce((pre, cur) => pre + cur.file.size, 0)

    // plimit 并发上传
    const uploadLimit = needUploadFile.map((n) =>
      limit(() => uploadChunkUrl(n, index, totalSize, item.file.type)),
    )

    const results = await Promise.allSettled(uploadLimit)
    const errResults = results.filter((r) => r.status === 'rejected')

    if (errResults.length > 0) {
      console.warn(item.name + ' 上传失败的分片-----', errResults)
      const _dataSource = [...getDataSource()]
      _dataSource[index].status = 'error'
      setDataSource(_dataSource)
      return
    }

    try {
      const { code, data } = await mergeFileByMd5(item.md5)
      if (code === 200) {
        console.log(data)
        const _dataSource = [...getDataSource()]
        _dataSource[index].progress = 100
        _dataSource[index].status = 'success'
        setDataSource(_dataSource)
      }
    } catch (error) {
      const _dataSource = [...getDataSource()]
      _dataSource[index].status = 'error'
      setDataSource(_dataSource)
    }
  }

  // 初始化分片操作并将分片文件和其上传地址一一对应
  const initSliceFile = async (item: FileTableDataType, initData: UploadFileInfoType) => {
    //  只有上传中的分片文件才会有 initData 数据，用 {} 做兜底
    const { uploadId, listParts } = initData || {}

    // 初始化分片参数
    const param: UploadFileInfoType = {
      uploadId,
      originFileName: item.name,
      size: item.size,
      chunkSize: CHUNK_SIZE,
      chunkCount: item.chunkCount,
      md5: item.md5,
      contentType: item.file.type,
    }

    const needUploadFile: ChunkFileUrlType[] = []

    const { code, data } = await initMultPartFile(param)
    if (code !== 200) return []

    // 存放需要去上传的文件数据
    if ((listParts || []).length == 0) {
      // 若全都没有上传，一一对应，其中 urls 是所有分片上传的 url 集合
      item.chunkFileList.forEach((item, index) => {
        needUploadFile.push({ url: data.urls[index], file: item })
      })
      return needUploadFile
    }

    // 存在上传的，对比 minio 已上传的 listParts（序号），将已上传的过滤掉，只上传未上传的文件
    item.chunkFileList.forEach((item, index) => {
      // listParts 索引是从 1 开始的
      const i = (listParts || []).findIndex((v) => index + 1 == v)
      if (i === -1) {
        needUploadFile.push({ url: data.urls[index], file: item })
      }
    })

    return needUploadFile
  }

  // 根据分片上传地址将分片直传至 minio
  const uploadChunkUrl = (
    chunkItem: ChunkFileUrlType,
    i: number,
    totalSize: number,
    type: string,
  ): Promise<void> => {
    return new Promise((resolve, reject) => {
      axios
        .put(chunkItem.url, chunkItem.file, {
          headers: { 'Content-Type': type || 'application/octet-stream' },
        })
        .then((res) => {
          if (res.status !== 200) {
            reject(chunkItem)
          } else {
            // 已上传的文件大小更新，上传进度更新
            const _dataSource = [...getDataSource()]

            const newUploaedSize = _dataSource[i].uploadedSize + chunkItem.file.size
            _dataSource[i] = {
              ..._dataSource[i],
              uploadedSize: newUploaedSize,
              progress: Math.floor((newUploaedSize / totalSize) * 100),
            }
            setDataSource(_dataSource)
            resolve()
          }
        })
        .catch((err) => {
          console.error(err)
          reject(chunkItem)
        })
    })
  }

  return (
    <Modal title="上传" open={open} width={800} footer={null} onCancel={onCancel}>
      <Card
        size="small"
        title={
          <Space>
            <Upload beforeUpload={() => false} onChange={selectFile} showUploadList={false}>
              <Button type="primary" icon={<UploadOutlined />}>
                选择文件
              </Button>
            </Upload>
            <Button type="primary" icon={<CloudUploadOutlined />} onClick={onUpload}>
              上传文件
            </Button>
          </Space>
        }
      >
        <List
          size="small"
          dataSource={dataSource}
          renderItem={(item) => (
            <List.Item key={item.uid}>
              <List.Item.Meta
                title={item.name}
                description={
                  <>
                    <Space size={30}>
                      <div>
                        大小: <span style={{ color: 'blue' }}>{item.unitSize}</span>
                      </div>
                      <div>
                        md5:{' '}
                        <span style={{ color: 'red' }}>
                          {item.md5 || (!!item.md5Progress && `${item.md5Progress}%`)}
                        </span>
                      </div>
                    </Space>
                    {!!item.progress && <Progress size="small" percent={item.progress} />}
                  </>
                }
              />
              <Tag color={tagMap[item.status].color}>{tagMap[item.status].text}</Tag>
            </List.Item>
          )}
        />
      </Card>
    </Modal>
  )
})

export default UploadModal
