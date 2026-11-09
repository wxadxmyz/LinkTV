/**
 * 频道数据源 channels.js
 * ------------------------------------------------------------------
 * 说明：
 *  - 央视使用央视官方 HLS（.m3u8），相对最稳。
 *  - 卫视 / 各省地方台为公开示例，url 留空（""）表示该台"源待补"，
 *    运行时播放会提示"暂未配置可用源"。请在下方或 App 内「导入M3U」补源。
 *  - 公开 IPTV 源随时可能失效，请自行维护；结构随便改，App 自动按分组渲染。
 */

const CHANNEL_GROUPS = [
  {
    "name": "央视",
    "channels": [
      {
        "name": "CCTV-1 综合",
        "url": "https://cctvcnch5ca.live.cctv.cn/whas/cctv1hd.m3u8"
      },
      {
        "name": "CCTV-2 财经",
        "url": "https://cctvcnch5ca.live.cctv.cn/whas/cctv2hd.m3u8"
      },
      {
        "name": "CCTV-3 综艺",
        "url": "https://cctvcnch5ca.live.cctv.cn/whas/cctv3hd.m3u8"
      },
      {
        "name": "CCTV-4 中文国际",
        "url": "https://cctvcnch5ca.live.cctv.cn/whas/cctv4hd.m3u8"
      },
      {
        "name": "CCTV-5 体育",
        "url": "https://cctvcnch5ca.live.cctv.cn/whas/cctv5hd.m3u8"
      },
      {
        "name": "CCTV-5+ 体育赛事",
        "url": "https://cctvcnch5ca.live.cctv.cn/whas/cctv5phd.m3u8"
      },
      {
        "name": "CCTV-6 电影",
        "url": "https://cctvcnch5ca.live.cctv.cn/whas/cctv6hd.m3u8"
      },
      {
        "name": "CCTV-7 国防军事",
        "url": "https://cctvcnch5ca.live.cctv.cn/whas/cctv7hd.m3u8"
      },
      {
        "name": "CCTV-8 电视剧",
        "url": "https://cctvcnch5ca.live.cctv.cn/whas/cctv8hd.m3u8"
      },
      {
        "name": "CCTV-9 纪录",
        "url": "https://cctvcnch5ca.live.cctv.cn/whas/cctv9hd.m3u8"
      },
      {
        "name": "CCTV-10 科教",
        "url": "https://cctvcnch5ca.live.cctv.cn/whas/cctv10hd.m3u8"
      },
      {
        "name": "CCTV-11 戏曲",
        "url": "https://cctvcnch5ca.live.cctv.cn/whas/cctv11hd.m3u8"
      },
      {
        "name": "CCTV-12 社会与法",
        "url": "https://cctvcnch5ca.live.cctv.cn/whas/cctv12hd.m3u8"
      },
      {
        "name": "CCTV-13 新闻",
        "url": "https://cctvcnch5ca.live.cctv.cn/whas/cctv13hd.m3u8"
      },
      {
        "name": "CCTV-14 少儿",
        "url": "https://cctvcnch5ca.live.cctv.cn/whas/cctv14hd.m3u8"
      },
      {
        "name": "CCTV-15 音乐",
        "url": "https://cctvcnch5ca.live.cctv.cn/whas/cctv15hd.m3u8"
      },
      {
        "name": "CCTV-16 奥林匹克",
        "url": "https://cctvcnch5ca.live.cctv.cn/whas/cctv16hd.m3u8"
      },
      {
        "name": "CCTV-17 农业农村",
        "url": "https://cctvcnch5ca.live.cctv.cn/whas/cctv17hd.m3u8"
      },
      {
        "name": "CCTV-4 欧洲",
        "url": "https://cctvcnch5ca.live.cctv.cn/whas/cctv4ouzhouhd.m3u8"
      },
      {
        "name": "CCTV-4 美洲",
        "url": "https://cctvcnch5ca.live.cctv.cn/whas/cctv4meizhouhd.m3u8"
      }
    ]
  },
  {
    "name": "卫视",
    "channels": [
      {
        "name": "北京卫视",
        "url": "",
        "prov": "北京",
        "region": "华北"
      },
      {
        "name": "天津卫视",
        "url": "",
        "prov": "天津",
        "region": "华北"
      },
      {
        "name": "河北卫视",
        "url": "",
        "prov": "河北",
        "region": "华北"
      },
      {
        "name": "山西卫视",
        "url": "",
        "prov": "山西",
        "region": "华北"
      },
      {
        "name": "内蒙古卫视",
        "url": "",
        "prov": "内蒙古",
        "region": "华北"
      },
      {
        "name": "辽宁卫视",
        "url": "",
        "prov": "辽宁",
        "region": "东北"
      },
      {
        "name": "吉林卫视",
        "url": "",
        "prov": "吉林",
        "region": "东北"
      },
      {
        "name": "黑龙江卫视",
        "url": "",
        "prov": "黑龙江",
        "region": "东北"
      },
      {
        "name": "东方卫视",
        "url": "",
        "prov": "上海",
        "region": "华东"
      },
      {
        "name": "江苏卫视",
        "url": "",
        "prov": "江苏",
        "region": "华东"
      },
      {
        "name": "浙江卫视",
        "url": "",
        "prov": "浙江",
        "region": "华东"
      },
      {
        "name": "安徽卫视",
        "url": "",
        "prov": "安徽",
        "region": "华东"
      },
      {
        "name": "福建卫视",
        "url": "",
        "prov": "福建",
        "region": "华东"
      },
      {
        "name": "东南卫视",
        "url": "",
        "prov": "福建",
        "region": "华东"
      },
      {
        "name": "厦门卫视",
        "url": "",
        "prov": "福建",
        "region": "华东"
      },
      {
        "name": "江西卫视",
        "url": "",
        "prov": "江西",
        "region": "华东"
      },
      {
        "name": "山东卫视",
        "url": "",
        "prov": "山东",
        "region": "华东"
      },
      {
        "name": "河南卫视",
        "url": "",
        "prov": "河南",
        "region": "华中"
      },
      {
        "name": "湖北卫视",
        "url": "",
        "prov": "湖北",
        "region": "华中"
      },
      {
        "name": "湖南卫视",
        "url": "",
        "prov": "湖南",
        "region": "华中"
      },
      {
        "name": "广东卫视",
        "url": "",
        "prov": "广东",
        "region": "华南"
      },
      {
        "name": "深圳卫视",
        "url": "",
        "prov": "广东",
        "region": "华南"
      },
      {
        "name": "广西卫视",
        "url": "",
        "prov": "广西",
        "region": "华南"
      },
      {
        "name": "海南卫视",
        "url": "",
        "prov": "海南",
        "region": "华南"
      },
      {
        "name": "重庆卫视",
        "url": "",
        "prov": "重庆",
        "region": "西南"
      },
      {
        "name": "四川卫视",
        "url": "",
        "prov": "四川",
        "region": "西南"
      },
      {
        "name": "贵州卫视",
        "url": "",
        "prov": "贵州",
        "region": "西南"
      },
      {
        "name": "云南卫视",
        "url": "",
        "prov": "云南",
        "region": "西南"
      },
      {
        "name": "西藏卫视",
        "url": "",
        "prov": "西藏",
        "region": "西南"
      },
      {
        "name": "陕西卫视",
        "url": "",
        "prov": "陕西",
        "region": "西北"
      },
      {
        "name": "甘肃卫视",
        "url": "",
        "prov": "甘肃",
        "region": "西北"
      },
      {
        "name": "青海卫视",
        "url": "",
        "prov": "青海",
        "region": "西北"
      },
      {
        "name": "宁夏卫视",
        "url": "",
        "prov": "宁夏",
        "region": "西北"
      },
      {
        "name": "新疆卫视",
        "url": "",
        "prov": "新疆",
        "region": "西北"
      },
      {
        "name": "凤凰卫视中文",
        "url": "",
        "prov": "香港",
        "region": "港澳台"
      },
      {
        "name": "凤凰卫视资讯",
        "url": "",
        "prov": "香港",
        "region": "港澳台"
      }
    ]
  },
  {
    "name": "地方台",
    "channels": [
      {
        "name": "北京卫视",
        "url": "",
        "prov": "北京",
        "region": "华北"
      },
      {
        "name": "北京新闻",
        "url": "",
        "prov": "北京",
        "region": "华北"
      },
      {
        "name": "北京文艺",
        "url": "",
        "prov": "北京",
        "region": "华北"
      },
      {
        "name": "北京影视",
        "url": "",
        "prov": "北京",
        "region": "华北"
      },
      {
        "name": "北京科教",
        "url": "",
        "prov": "北京",
        "region": "华北"
      },
      {
        "name": "北京体育",
        "url": "",
        "prov": "北京",
        "region": "华北"
      },
      {
        "name": "北京生活",
        "url": "",
        "prov": "北京",
        "region": "华北"
      },
      {
        "name": "北京少儿",
        "url": "",
        "prov": "北京",
        "region": "华北"
      },
      {
        "name": "北京卡酷少儿",
        "url": "",
        "prov": "北京",
        "region": "华北"
      },
      {
        "name": "北京国际",
        "url": "",
        "prov": "北京",
        "region": "华北"
      },
      {
        "name": "天津卫视",
        "url": "",
        "prov": "天津",
        "region": "华北"
      },
      {
        "name": "天津新闻",
        "url": "",
        "prov": "天津",
        "region": "华北"
      },
      {
        "name": "天津文艺",
        "url": "",
        "prov": "天津",
        "region": "华北"
      },
      {
        "name": "天津都市",
        "url": "",
        "prov": "天津",
        "region": "华北"
      },
      {
        "name": "河北卫视",
        "url": "",
        "prov": "河北",
        "region": "华北"
      },
      {
        "name": "河北经济",
        "url": "",
        "prov": "河北",
        "region": "华北"
      },
      {
        "name": "河北农民",
        "url": "",
        "prov": "河北",
        "region": "华北"
      },
      {
        "name": "石家庄新闻",
        "url": "",
        "prov": "河北",
        "region": "华北"
      },
      {
        "name": "山西卫视",
        "url": "",
        "prov": "山西",
        "region": "华北"
      },
      {
        "name": "山西科教",
        "url": "",
        "prov": "山西",
        "region": "华北"
      },
      {
        "name": "太原新闻",
        "url": "",
        "prov": "山西",
        "region": "华北"
      },
      {
        "name": "内蒙古卫视",
        "url": "",
        "prov": "内蒙古",
        "region": "华北"
      },
      {
        "name": "内蒙古蒙语",
        "url": "",
        "prov": "内蒙古",
        "region": "华北"
      },
      {
        "name": "呼和浩特新闻",
        "url": "",
        "prov": "内蒙古",
        "region": "华北"
      },
      {
        "name": "辽宁卫视",
        "url": "",
        "prov": "辽宁",
        "region": "东北"
      },
      {
        "name": "辽宁都市",
        "url": "",
        "prov": "辽宁",
        "region": "东北"
      },
      {
        "name": "大连新闻",
        "url": "",
        "prov": "辽宁",
        "region": "东北"
      },
      {
        "name": "吉林卫视",
        "url": "",
        "prov": "吉林",
        "region": "东北"
      },
      {
        "name": "吉林乡村",
        "url": "",
        "prov": "吉林",
        "region": "东北"
      },
      {
        "name": "长春新闻",
        "url": "",
        "prov": "吉林",
        "region": "东北"
      },
      {
        "name": "黑龙江卫视",
        "url": "",
        "prov": "黑龙江",
        "region": "东北"
      },
      {
        "name": "黑龙江都市",
        "url": "",
        "prov": "黑龙江",
        "region": "东北"
      },
      {
        "name": "哈尔滨新闻",
        "url": "",
        "prov": "黑龙江",
        "region": "东北"
      },
      {
        "name": "东方卫视",
        "url": "",
        "prov": "上海",
        "region": "华东"
      },
      {
        "name": "上海新闻综合",
        "url": "",
        "prov": "上海",
        "region": "华东"
      },
      {
        "name": "上海娱乐",
        "url": "",
        "prov": "上海",
        "region": "华东"
      },
      {
        "name": "上海戏剧",
        "url": "",
        "prov": "上海",
        "region": "华东"
      },
      {
        "name": "上海纪实",
        "url": "",
        "prov": "上海",
        "region": "华东"
      },
      {
        "name": "上海东方影视",
        "url": "",
        "prov": "上海",
        "region": "华东"
      },
      {
        "name": "江苏卫视",
        "url": "",
        "prov": "江苏",
        "region": "华东"
      },
      {
        "name": "江苏城市",
        "url": "",
        "prov": "江苏",
        "region": "华东"
      },
      {
        "name": "江苏教育",
        "url": "",
        "prov": "江苏",
        "region": "华东"
      },
      {
        "name": "南京新闻",
        "url": "",
        "prov": "江苏",
        "region": "华东"
      },
      {
        "name": "浙江卫视",
        "url": "",
        "prov": "浙江",
        "region": "华东"
      },
      {
        "name": "浙江民生休闲",
        "url": "",
        "prov": "浙江",
        "region": "华东"
      },
      {
        "name": "浙江少儿",
        "url": "",
        "prov": "浙江",
        "region": "华东"
      },
      {
        "name": "杭州综合",
        "url": "",
        "prov": "浙江",
        "region": "华东"
      },
      {
        "name": "安徽卫视",
        "url": "",
        "prov": "安徽",
        "region": "华东"
      },
      {
        "name": "安徽经济",
        "url": "",
        "prov": "安徽",
        "region": "华东"
      },
      {
        "name": "安徽公共",
        "url": "",
        "prov": "安徽",
        "region": "华东"
      },
      {
        "name": "福建卫视",
        "url": "",
        "prov": "福建",
        "region": "华东"
      },
      {
        "name": "东南卫视",
        "url": "",
        "prov": "福建",
        "region": "华东"
      },
      {
        "name": "福建新闻",
        "url": "",
        "prov": "福建",
        "region": "华东"
      },
      {
        "name": "厦门卫视",
        "url": "",
        "prov": "福建",
        "region": "华东"
      },
      {
        "name": "江西卫视",
        "url": "",
        "prov": "江西",
        "region": "华东"
      },
      {
        "name": "江西都市",
        "url": "",
        "prov": "江西",
        "region": "华东"
      },
      {
        "name": "南昌新闻",
        "url": "",
        "prov": "江西",
        "region": "华东"
      },
      {
        "name": "山东卫视",
        "url": "",
        "prov": "山东",
        "region": "华东"
      },
      {
        "name": "山东齐鲁",
        "url": "",
        "prov": "山东",
        "region": "华东"
      },
      {
        "name": "山东农科",
        "url": "",
        "prov": "山东",
        "region": "华东"
      },
      {
        "name": "济南新闻",
        "url": "",
        "prov": "山东",
        "region": "华东"
      },
      {
        "name": "河南卫视",
        "url": "",
        "prov": "河南",
        "region": "华中"
      },
      {
        "name": "河南都市",
        "url": "",
        "prov": "河南",
        "region": "华中"
      },
      {
        "name": "河南新闻",
        "url": "",
        "prov": "河南",
        "region": "华中"
      },
      {
        "name": "湖北卫视",
        "url": "",
        "prov": "湖北",
        "region": "华中"
      },
      {
        "name": "湖北综合",
        "url": "",
        "prov": "湖北",
        "region": "华中"
      },
      {
        "name": "武汉新闻",
        "url": "",
        "prov": "湖北",
        "region": "华中"
      },
      {
        "name": "湖南卫视",
        "url": "",
        "prov": "湖南",
        "region": "华中"
      },
      {
        "name": "湖南经视",
        "url": "",
        "prov": "湖南",
        "region": "华中"
      },
      {
        "name": "湖南娱乐",
        "url": "",
        "prov": "湖南",
        "region": "华中"
      },
      {
        "name": "湖南都市",
        "url": "",
        "prov": "湖南",
        "region": "华中"
      },
      {
        "name": "长沙新闻",
        "url": "",
        "prov": "湖南",
        "region": "华中"
      },
      {
        "name": "广东卫视",
        "url": "",
        "prov": "广东",
        "region": "华南"
      },
      {
        "name": "广东珠江",
        "url": "",
        "prov": "广东",
        "region": "华南"
      },
      {
        "name": "广东新闻",
        "url": "",
        "prov": "广东",
        "region": "华南"
      },
      {
        "name": "广州综合",
        "url": "",
        "prov": "广东",
        "region": "华南"
      },
      {
        "name": "深圳卫视",
        "url": "",
        "prov": "广东",
        "region": "华南"
      },
      {
        "name": "深圳都市",
        "url": "",
        "prov": "广东",
        "region": "华南"
      },
      {
        "name": "深圳财经",
        "url": "",
        "prov": "广东",
        "region": "华南"
      },
      {
        "name": "广西卫视",
        "url": "",
        "prov": "广西",
        "region": "华南"
      },
      {
        "name": "广西都市",
        "url": "",
        "prov": "广西",
        "region": "华南"
      },
      {
        "name": "南宁新闻",
        "url": "",
        "prov": "广西",
        "region": "华南"
      },
      {
        "name": "海南卫视",
        "url": "",
        "prov": "海南",
        "region": "华南"
      },
      {
        "name": "海南新闻",
        "url": "",
        "prov": "海南",
        "region": "华南"
      },
      {
        "name": "海口新闻",
        "url": "",
        "prov": "海南",
        "region": "华南"
      },
      {
        "name": "重庆卫视",
        "url": "",
        "prov": "重庆",
        "region": "西南"
      },
      {
        "name": "重庆新闻",
        "url": "",
        "prov": "重庆",
        "region": "西南"
      },
      {
        "name": "重庆影视",
        "url": "",
        "prov": "重庆",
        "region": "西南"
      },
      {
        "name": "四川卫视",
        "url": "",
        "prov": "四川",
        "region": "西南"
      },
      {
        "name": "四川文化旅游",
        "url": "",
        "prov": "四川",
        "region": "西南"
      },
      {
        "name": "成都新闻",
        "url": "",
        "prov": "四川",
        "region": "西南"
      },
      {
        "name": "贵州卫视",
        "url": "",
        "prov": "贵州",
        "region": "西南"
      },
      {
        "name": "贵州新闻",
        "url": "",
        "prov": "贵州",
        "region": "西南"
      },
      {
        "name": "贵阳新闻",
        "url": "",
        "prov": "贵州",
        "region": "西南"
      },
      {
        "name": "云南卫视",
        "url": "",
        "prov": "云南",
        "region": "西南"
      },
      {
        "name": "云南都市",
        "url": "",
        "prov": "云南",
        "region": "西南"
      },
      {
        "name": "昆明新闻",
        "url": "",
        "prov": "云南",
        "region": "西南"
      },
      {
        "name": "西藏卫视",
        "url": "",
        "prov": "西藏",
        "region": "西南"
      },
      {
        "name": "西藏藏语",
        "url": "",
        "prov": "西藏",
        "region": "西南"
      },
      {
        "name": "拉萨新闻",
        "url": "",
        "prov": "西藏",
        "region": "西南"
      },
      {
        "name": "陕西卫视",
        "url": "",
        "prov": "陕西",
        "region": "西北"
      },
      {
        "name": "陕西新闻",
        "url": "",
        "prov": "陕西",
        "region": "西北"
      },
      {
        "name": "西安新闻",
        "url": "",
        "prov": "陕西",
        "region": "西北"
      },
      {
        "name": "甘肃卫视",
        "url": "",
        "prov": "甘肃",
        "region": "西北"
      },
      {
        "name": "甘肃都市",
        "url": "",
        "prov": "甘肃",
        "region": "西北"
      },
      {
        "name": "兰州新闻",
        "url": "",
        "prov": "甘肃",
        "region": "西北"
      },
      {
        "name": "青海卫视",
        "url": "",
        "prov": "青海",
        "region": "西北"
      },
      {
        "name": "青海新闻",
        "url": "",
        "prov": "青海",
        "region": "西北"
      },
      {
        "name": "西宁新闻",
        "url": "",
        "prov": "青海",
        "region": "西北"
      },
      {
        "name": "宁夏卫视",
        "url": "",
        "prov": "宁夏",
        "region": "西北"
      },
      {
        "name": "宁夏公共",
        "url": "",
        "prov": "宁夏",
        "region": "西北"
      },
      {
        "name": "银川新闻",
        "url": "",
        "prov": "宁夏",
        "region": "西北"
      },
      {
        "name": "新疆卫视",
        "url": "",
        "prov": "新疆",
        "region": "西北"
      },
      {
        "name": "新疆维语",
        "url": "",
        "prov": "新疆",
        "region": "西北"
      },
      {
        "name": "乌鲁木齐新闻",
        "url": "",
        "prov": "新疆",
        "region": "西北"
      },
      {
        "name": "翡翠台",
        "url": "",
        "prov": "香港",
        "region": "港澳台"
      },
      {
        "name": "明珠台",
        "url": "",
        "prov": "香港",
        "region": "港澳台"
      },
      {
        "name": "无线新闻台",
        "url": "",
        "prov": "香港",
        "region": "港澳台"
      },
      {
        "name": "凤凰卫视中文",
        "url": "",
        "prov": "香港",
        "region": "港澳台"
      },
      {
        "name": "凤凰卫视资讯",
        "url": "",
        "prov": "香港",
        "region": "港澳台"
      },
      {
        "name": "澳广视",
        "url": "",
        "prov": "澳门",
        "region": "港澳台"
      },
      {
        "name": "澳门综艺",
        "url": "",
        "prov": "澳门",
        "region": "港澳台"
      },
      {
        "name": "台视",
        "url": "",
        "prov": "台湾",
        "region": "港澳台"
      },
      {
        "name": "中视",
        "url": "",
        "prov": "台湾",
        "region": "港澳台"
      },
      {
        "name": "华视",
        "url": "",
        "prov": "台湾",
        "region": "港澳台"
      },
      {
        "name": "民视",
        "url": "",
        "prov": "台湾",
        "region": "港澳台"
      }
    ]
  }
];

// 可选：一个远程 M3U 播放列表地址，App 启动时可一键拉取合并。留空则不使用。
const REMOTE_M3U_URL = "";

window.CHANNEL_GROUPS = CHANNEL_GROUPS;
window.REMOTE_M3U_URL = REMOTE_M3U_URL;
