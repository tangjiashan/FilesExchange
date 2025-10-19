# windows client运行结构
> 运行windows客户端时，将config.json与编译好的exe文件放在相同目录
![](../images/windowsclient.png)

> 修改config.json中用于接收文件的目录名称
```
{
  "broker": "tcp://broker.emqx.io:1883",
  "download_dir": "C:\\Users\\Cindy\\Downloads\\sdes\\files"
}
```