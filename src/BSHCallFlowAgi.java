import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.asteriskjava.fastagi.AgiChannel;
import org.asteriskjava.fastagi.AgiException;
import org.asteriskjava.fastagi.AgiRequest;
import org.asteriskjava.fastagi.BaseAgiScript;

import com.callke8.bsh.bshcallparam.BSHCallParamConfig;
import com.callke8.bsh.bshorderlist.BSHHttpRequestThread;
import com.callke8.bsh.bshorderlist.BSHOrderList;
import com.callke8.bsh.bshvoice.BSHVoice;
import com.callke8.bsh.bshvoice.BSHVoiceConfig;
import com.callke8.predialqueuforbsh.BSHLaunchDialService;
import com.callke8.pridialqueueforbshbyquartz.BSHPredial;
import com.callke8.utils.BlankUtils;
import com.callke8.utils.DateFormatUtils;
import com.callke8.utils.StringUtil;
import com.jfinal.plugin.activerecord.Record;

public class BSHCallFlowAgi extends BaseAgiScript {

	private Log log = LogFactory.getLog(BSHCallFlowAgi.class);
	
	@Override
	public void service(AgiRequest request, AgiChannel channel) throws AgiException {
		channel.exec("Noop","执行到了 BSHCallFlowAgi-----");
		//执行到这里之后，由于 Avaya 传过来的状态有可能出现这种情况：通道已接听，但是在 BSHLaunchDialService 的返回的呼叫状态结果中可能还是出现了 NOANSWER 状态
		
		
		String bshOrderListId = channel.getVariable("bshOrderListId");
		StringUtil.writeString("/opt/dial-log.log",DateFormatUtils.getCurrentDate() + ",/(流程执行):bshOrderListId " + bshOrderListId + ",通道标识:" + channel.getName(), true);
		
		//List<Record> playList = new ArrayList<Record>();		 		//定义一个List，用于储存插入列表 
		
		StringBuilder readFileSb = new StringBuilder();				    //定义一个用于储存read命令所需语音文件字符串
		
		List<Record> respond1PlayList = new ArrayList<Record>();        //定义一个当回复1，即是确认安装后播放的语音列表
		List<Record> respond2PlayList = new ArrayList<Record>();        //定义一个当回复2，即是暂不安装后播放的语音列表
		List<Record> respond3PlayList = new ArrayList<Record>();        //定义一个当回复3，即是延后安装后播放的语音列表
		List<Record> respond4PlayList = new ArrayList<Record>();        //定义一个当无回复或是错误回复，播放的语音列表
		
		exec("Noop","bshOrderListId-----" + bshOrderListId);
		//从数据表中取出订单信息
		BSHOrderList bshOrderList = BSHOrderList.dao.getBSHOrderListById(bshOrderListId);
		//System.out.println("取出的订单信息为---=========：" + bshOrderList);
		exec("Noop","AGI流程得到的订单ID为：" + bshOrderListId + ",订单详情：" + bshOrderList);
		StringUtil.writeString("/opt/dial-log.log",DateFormatUtils.getCurrentDate() + ",FastAGI11111(流程执行)：" + bshOrderList.getStr("CUSTOMER_TEL") + ",通道标识:" + channel.getName(), true);
		
		//playList = getPlayList(bshOrderList);     //组织播放开始语音
		String readVoiceFileList = getReadVoiceFileToString(bshOrderList);   //Read应用所需语音文件
		
		respond1PlayList = getRespond1PlayList(bshOrderList);
		respond2PlayList = getRespond2PlayList(bshOrderList);
		respond3PlayList = getRespond3PlayList(bshOrderList);
		respond4PlayList = getRespond4PlayList(bshOrderList);
			
		//如果开始播放列表不为空时
		if(!BlankUtils.isBlank(readVoiceFileList)) {
			
			exec("Noop","Read播放文件列表内容:" + readVoiceFileList);
			exec("Wait","1");
			
			execRead(readVoiceFileList,respond1PlayList, respond2PlayList, respond3PlayList, respond4PlayList,bshOrderList, channel);     //执行调查操作
			
			StringUtil.writeString("/opt/dial-log.log",DateFormatUtils.getCurrentDate() + ",FastAGI22222(流程执行结束)：" + bshOrderList.getStr("CUSTOMER_TEL") + ",通道标识:" + channel.getName(), true);
			
		}else {
			exec("Noop","Read播放文件列表内容为空!系统无法调查客户!");
		}
		
		
	}
	
	public void execRead(String readVoiceFileList,List<Record> respond1PlayList,List<Record> respond2PlayList,List<Record> respond3PlayList,List<Record> respond4PlayList,BSHOrderList bshOrderList,AgiChannel channel) {
		
		try {
			
			exec("Read","respond," + readVoiceFileList + ",1,,1,8");
			
			String respond = channel.getVariable("respond");     //取得回复结果
			
			//一共要求两次，如果客户第一次回复为空或是错误回复时，再执行一次。
			if(BlankUtils.isBlank(respond) || !(respond.equalsIgnoreCase("1") || respond.equalsIgnoreCase("2") || respond.equalsIgnoreCase("3"))) {
				exec("Read","respond," + readVoiceFileList + ",1,,1,8");
				respond = channel.getVariable("respond");     //再次取得回复结果
			}
			
			if(!BlankUtils.isBlank(respond)) {      //客户回复不为空时
				
				if(respond.equalsIgnoreCase("1")) {     		//如果回复的是1时,确认安装
					
					exec("Noop","客户" + bshOrderList.get("CUSTOMER_TEL") + "回复1,即确认安装");
					//更改客户回复的同时，将呼叫状态更改为2，即是外呼成功
					BSHOrderList.dao.updateBSHOrderListRespond(bshOrderList.get("ID").toString(), respond);
					
					execPlayBack(respond1PlayList);     //回复后，还需要将结果播放回去
				}else if(respond.equalsIgnoreCase("2")) {		//如果回复的是2时，暂不安装
					
					exec("Noop","客户" + bshOrderList.get("CUSTOMER_TEL") + "回复2,即暂不安装");
					//更改客户回复的同时，将呼叫状态更改为2，即是外呼成功
					BSHOrderList.dao.updateBSHOrderListRespond(bshOrderList.get("ID").toString(), respond);
				
					execPlayBack(respond2PlayList);     //回复后，还需要将结果播放回去
					
				}else if(respond.equalsIgnoreCase("3")) {       //如果回复的是3时，延后安装
					exec("Noop","客户" + bshOrderList.get("CUSTOMER_TEL") + "回复3,即延后安装");
					//更改客户回复的同时，将呼叫状态更改为2，即是外呼成功
					BSHOrderList.dao.updateBSHOrderListRespond(bshOrderList.get("ID").toString(), respond);
					
					execPlayBack(respond3PlayList);     //回复后，还需要将结果播放回去
					
				}else {                       //如果回复的是其他按键时,按回复
					exec("Noop","客户 " + bshOrderList.get("CUSTOMER_TEL") + "回复" + respond + ",即为错误回复");
					respond = "4";            //强制为错误回复
					//更改客户回复的同时，将呼叫状态更改为2，即是外呼成功
					BSHOrderList.dao.updateBSHOrderListRespond(bshOrderList.get("ID").toString(), respond);
					
					execPlayBack(respond4PlayList);     //回复后，还需要将结果播放回去
				}
				
			}else {
				exec("Noop","客户" + bshOrderList.get("CUSTOMER_TEL") + "无回复任何");
				respond = "4";
				BSHOrderList.dao.updateBSHOrderListRespond(bshOrderList.get("ID").toString(), respond);
				
				execPlayBack(respond4PlayList);     //回复后，还需要将结果播放回去
			}
			
			//需要将客户回复结果返回给BSH服务器
			//同时，将呼叫成功结果反馈给 BSH 服务器
			BSHHttpRequestThread httpRequestT = new BSHHttpRequestThread(bshOrderList.get("ID").toString(),bshOrderList.getStr("ORDER_ID"), "1", String.valueOf(respond));
			Thread httpRequestThread = new Thread(httpRequestT);
			httpRequestThread.start();
			
			//无论是否回复什么结果，或是没有回复结果,在这里表示外呼已经结束，需要将活跃通道减掉一个
			if(BSHPredial.activeChannelCount > 0) {        
				BSHPredial.activeChannelCount--;
			}
			
		} catch (AgiException e) {
			e.printStackTrace();
		}
		
	}
	
	/**
	 * 执行播放
	 * 
	 * @param playList
	 * @param bshOrderListId
	 * @param channel
	 */
	public void execPlay(List<Record> playList,List<Record> respond1PlayList,List<Record> respond2PlayList,List<Record> respond3PlayList,List<Record> respond4PlayList,BSHOrderList bshOrderList,AgiChannel channel) {
		
		//如果插入列表不为空时
		if(!BlankUtils.isBlank(playList) && playList.size()>0) {
			
			for(Record record:playList) {
				
				String action = record.get("action");
				String path = record.get("path");
				
				if(action.equalsIgnoreCase("Read")) {      //如果为Read的应用时，表示需要等待客户回复
					
					try {
						
						exec(action,path);           //执行播放并等待客户回复
						
						String respond = channel.getVariable("respond");
						
						if(!BlankUtils.isBlank(respond)) {      //客户回复不为空时
							
							if(respond.equalsIgnoreCase("1")) {     		//如果回复的是1时,确认安装
								
								exec("Noop","客户" + bshOrderList.get("CUSTOMER_TEL") + "回复1,即确认安装");
								//更改客户回复的同时，将呼叫状态更改为2，即是外呼成功
								BSHOrderList.dao.updateBSHOrderListRespond(bshOrderList.get("ID").toString(), respond);
								
								execPlayBack(respond1PlayList);     //回复后，还需要将结果播放回去
							}else if(respond.equalsIgnoreCase("2")) {		//如果回复的是2时，暂不安装
								
								exec("Noop","客户" + bshOrderList.get("CUSTOMER_TEL") + "回复2,即暂不安装");
								//更改客户回复的同时，将呼叫状态更改为2，即是外呼成功
								BSHOrderList.dao.updateBSHOrderListRespond(bshOrderList.get("ID").toString(), respond);
							
								execPlayBack(respond2PlayList);     //回复后，还需要将结果播放回去
								
							}else if(respond.equalsIgnoreCase("3")) {       //如果回复的是3时，延后安装
								exec("Noop","客户" + bshOrderList.get("CUSTOMER_TEL") + "回复3,即延后安装");
								//更改客户回复的同时，将呼叫状态更改为2，即是外呼成功
								BSHOrderList.dao.updateBSHOrderListRespond(bshOrderList.get("ID").toString(), respond);
								
								execPlayBack(respond3PlayList);     //回复后，还需要将结果播放回去
								
							}else {                       //如果回复的是其他按键时,按回复
								exec("Noop","客户 " + bshOrderList.get("CUSTOMER_TEL") + "回复" + respond + ",即为错误回复");
								respond = "4";            //强制为错误回复
								//更改客户回复的同时，将呼叫状态更改为2，即是外呼成功
								BSHOrderList.dao.updateBSHOrderListRespond(bshOrderList.get("ID").toString(), respond);
								
								execPlayBack(respond4PlayList);     //回复后，还需要将结果播放回去
							}
							
						}else {
							exec("Noop","客户" + bshOrderList.get("CUSTOMER_TEL") + "无回复任何");
							respond = "4";
							//更改客户回复的同时，将呼叫状态更改为2，即是外呼成功
							BSHOrderList.dao.updateBSHOrderListRespond(bshOrderList.get("ID").toString(), respond);
							
							execPlayBack(respond4PlayList);     //回复后，还需要将结果播放回去
						}
						
						//无论是否回复什么结果，或是没有回复结果,在这里表示外呼已经结束，需要将活跃通道减掉一个
						BSHPredial.activeChannelCount--;
						
					} catch (AgiException e) {
						e.printStackTrace();
					}

				}else {					//如果PlayBack 就执行插放操作
					try {
						exec(action,path);
					} catch (AgiException e) {
						e.printStackTrace();
					}
				}
				
			}
			
		}
	}
	
	/**
	 * 播放语音
	 * @param playList
	 */
	public void execPlayBack(List<Record> playList) {
		
		//如果插入列表不为空时
		if(!BlankUtils.isBlank(playList) && playList.size()>0) {
			for(Record record:playList) {
				String action = record.get("action");
				String path = record.get("path");
				
				try {
					exec(action,path);
				} catch (AgiException e) {
					e.printStackTrace();
				}
				
			}
		}
		
	}
	
	/**
	 * 取得 Read 命令所需语音文件名字符串
	 * 一次性取得多个文件，形成完整的调查语音
	 * 
	 * @param bshOrderList
	 * @return
	 */
	public String getReadVoiceFileToString(BSHOrderList bshOrderList) {
		
		StringBuilder sb = new StringBuilder();
		
		String voicePath = BSHCallParamConfig.getVoicePathSingle();   //取出配置的语音文件（单声道）路径
		
		//(1)您好，这里是西门子家电客服中心,来电跟您确认
		int brand = bshOrderList.getInt("BRAND");           //取得品牌
		String voiceId1 = "Brand_" + brand;
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceId1)) {
			sb.append(voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceId1));
		}
		
		//(2)产品语音
		int productName = bshOrderList.getInt("PRODUCT_NAME");       //取得产品
		String voiceId2 = "ProductName_" + productName;
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceId2)) {
			sb.append("&");
			sb.append(voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceId2));
		}
		
		//(3)的安装日期
		String voiceId3 = "Notice_1";
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceId3)) {
			sb.append("&");
			sb.append(voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceId3));
		}
		
		//(4)根据京东平台传来的信息，我们将于
		int channelSource = bshOrderList.getInt("CHANNEL_SOURCE");   //取出平台信息
		String voiceId4 = "ChannelSource_" + channelSource;
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceId4)) {
			sb.append("&");
			sb.append(voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceId4));
		}
		
		//(5)5月12号
		String expectInstallDate = bshOrderList.getDate("EXPECT_INSTALL_DATE").toString();      //取出期望安装日期
		Date installDate = DateFormatUtils.parseDateTime(expectInstallDate, "yyyy-MM-dd");
		String monthStr = DateFormatUtils.formatDateTime(installDate, "MM");
		String dayStr = DateFormatUtils.formatDateTime(installDate,"dd");
		String voiceIdForMonth = "Month_" + monthStr;
		String voiceIdForDay = "Day_" + dayStr;
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceIdForMonth)) {
			sb.append("&");
			sb.append(voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceIdForMonth));
		}
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceIdForDay)) {
			sb.append("&");
			sb.append(voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceIdForDay));
		}
		
		//(6)上门安装
		String voiceId6 = "Notice_2";
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceId6)) {
			sb.append("&");
			sb.append(voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceId6));
		}
		
		//（7）确认安装请按“1”，暂不安装请按“2”，如需改约到后面3天，请按“3”。
		String voiceId7 = "ComfirmVoice";
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceId7)) {
			sb.append("&");
			sb.append(voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceId7));
		}
		
		return sb.toString();
		
	}
	
	/**
	 * 根据传入的订单信息，生成播放列表
	 * 
	 * 完整的调查流程是这样的：
	 * 您好，这里是博世家电客服中心,来电跟您确认 洗衣机 的安装日期。 根据京东平台传来的信息，我们将于 12月10号 上门安装。
	        确认安装请按“1”，暂不安装请按“2”，如需改约到后面3天，请按“3”。
	 * 
	 * 不过语音并不是一整段的，需要重新拼接
	 * 
	 * （1）您好，这里是博世家电客服中心,来电跟您确认 （2）洗衣机 （3）的安装日期。 （4）根据京东平台传来的信息，我们将于（5） 12月10号 （6）上门安装。
	        （7）确认安装请按“1”，暂不安装请按“2”，如需改约到后面3天，请按“3”。
	 * 
	 * @param bshOrderList
	 * @return
	 */
	public List<Record> getPlayList(BSHOrderList bshOrderList) {
		
		String voicePath = BSHCallParamConfig.getVoicePathSingle();
		List<Record> list = new ArrayList<Record>();        //新建一个List，用于储存语音
		//(1)您好，这里是西门子家电客服中心,来电跟您确认
		//	 您好，这里是博世家电客服中心,来电跟您确认
		int brand = bshOrderList.getInt("BRAND");           //取得品牌
		String voiceId1 = "Brand_" + brand;
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceId1)) {
			list.add(setRecord("wait","1"));         //先停顿1秒
			list.add(setRecord("PlayBack",voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceId1))); 
		}
		//(2)产品语音
		int productName = bshOrderList.getInt("PRODUCT_NAME");       //取得产品
		String voiceId2 = "ProductName_" + productName;
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceId2)) {
			list.add(setRecord("PlayBack",voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceId2))); 
		}
		//(3)的安装日期
		String voiceId3 = "Notice_1";
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceId3)) {
			list.add(setRecord("PlayBack",voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceId3))); 
		}
		//(4)根据京东平台传来的信息，我们将于
		int channelSource = bshOrderList.getInt("CHANNEL_SOURCE");   //取出平台信息
		String voiceId4 = "ChannelSource_" + channelSource;
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceId4)) {
			list.add(setRecord("wait","0.5"));         //先停顿1秒
			list.add(setRecord("PlayBack",voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceId4)));
			//list.add(setRecord("wait","1"));         //先停顿1秒
		}
		//(5)5月12号
		String expectInstallDate = bshOrderList.getDate("EXPECT_INSTALL_DATE").toString();      //取出期望安装日期
		Date installDate = DateFormatUtils.parseDateTime(expectInstallDate, "yyyy-MM-dd");
		String monthStr = DateFormatUtils.formatDateTime(installDate, "MM");
		String dayStr = DateFormatUtils.formatDateTime(installDate,"dd");
		String voiceIdForMonth = "Month_" + monthStr;
		String voiceIdForDay = "Day_" + dayStr;
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceIdForMonth)) {
			list.add(setRecord("PlayBack",voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceIdForMonth))); 
		}
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceIdForDay)) {
			list.add(setRecord("wait","0.5"));         //先停顿0.5秒
			list.add(setRecord("PlayBack",voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceIdForDay))); 
			//list.add(setRecord("wait","0.5"));         //先停顿0.5秒
		}
		
		//(6)上门安装
		String voiceId6 = "Notice_2";
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceId6)) {
			list.add(setRecord("PlayBack",voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceId6))); 
		}
		
		//（7）确认安装请按“1”，暂不安装请按“2”，如需改约到后面3天，请按“3”。
		String voiceId7 = "ComfirmVoice";
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceId7)) {
			String comfirmVoicePath = voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceId7);
			list.add(setRecord("wait","1"));         //先停顿1秒
			list.add(setRecord("Read","respond," + comfirmVoicePath + ",1,,2,8"));    //一个回复按键，2次播放，等待8秒
		}
		
		return list;
		
	}
	
	/**
	 * 客户回复1，即确认安装时
	 * 
	 * 您的机器安装日期已确认为 12月10号， 工程师最迟会在当天早上9：30之前与您联系具体上门时间。感谢您的配合，再见。
	 * 
	 * 并非一整段语音
	 * 
	 * （1）您的机器安装日期已确认为（2） 12月10号， （3）工程师最迟会在当天早上9：30之前与您联系具体上门时间。感谢您的配合，再见。
	 * 
	 * @param bshOrderList
	 * @return
	 */
	public List<Record> getRespond1PlayList(BSHOrderList bshOrderList) {
		
		String voicePath = BSHCallParamConfig.getVoicePathSingle();
		List<Record> list = new ArrayList<Record>();
		
		//（1）您的机器安装日期已确认为
		String voiceId1 = "Respond_1_0";
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceId1)) {
			list.add(setRecord("wait","1"));         //先停顿1秒
			list.add(setRecord("PlayBack",voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceId1))); 
			//list.add(setRecord("wait","1"));         //先停顿1秒
		}
		//（2）12月10号
		String expectInstallDate = bshOrderList.getDate("EXPECT_INSTALL_DATE").toString();      //取出期望安装日期
		Date installDate = DateFormatUtils.parseDateTime(expectInstallDate, "yyyy-MM-dd");
		String monthStr = DateFormatUtils.formatDateTime(installDate, "MM");
		String dayStr = DateFormatUtils.formatDateTime(installDate,"dd");
		
		String voiceIdForMonth = "Month_" + monthStr;
		String voiceIdForDay = "Day_" + dayStr;
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceIdForMonth)) {
			list.add(setRecord("PlayBack",voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceIdForMonth))); 
		}
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceIdForDay)) {
			list.add(setRecord("PlayBack",voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceIdForDay))); 
		}
		
		//（3）工程师最迟会在当天早上9：30之前与您联系具体上门时间。感谢您的配合，再见。
		String voiceId3 = "Respond_1_1";
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceId3)) {
			list.add(setRecord("wait","0.5"));         //先停顿1秒
			list.add(setRecord("PlayBack",voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceId3))); 
		}
		
		return list;
	}
	
	/**
	 * 回复2，即是暂不安装
	 * 
	 * 您的机器，暂时将不会安排上门安装。如后期仍然需要安装，欢迎拨打400-889-9999，或者关注西门子家电微信公众号预约。感谢您的配合，再见。
	 * 您的机器，暂时将不会安排上门安装。如后期仍然需要安装，欢迎拨打 400-885-5888 或者关注 “博世家电” 微信公众号预约。感谢您的配合，再见。
	 * 
	 * 根据品牌组织语音
	 * 
	 * @param bshOrderList
	 * @return
	 */
	public List<Record> getRespond2PlayList(BSHOrderList bshOrderList) {
		
		String voicePath = BSHCallParamConfig.getVoicePathSingle();
		List<Record> list = new ArrayList<Record>();
		
		int brand = bshOrderList.getInt("BRAND");           //取得品牌
		String voiceId1 = "Respond_2_" + brand;				
		
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceId1)) {
			list.add(setRecord("wait","0.5"));         //先停顿1秒
			list.add(setRecord("PlayBack",voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceId1))); 
		}
		
		return list;
	}
	
	/**
	 * 客户回复 3，延后安装
	 * 
	 * 稍后您会收到1条确认短信，请您按短信提示，直接回复数字即可。感谢您的配合，再见。
	 * @param bshOrderList
	 * @return
	 */
	public List<Record> getRespond3PlayList(BSHOrderList bshOrderList) {
		
		String voicePath = BSHCallParamConfig.getVoicePathSingle();
		List<Record> list = new ArrayList<Record>();
		
		//(1) 
		String voiceId1 = "Respond_3";
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceId1)) {
			list.add(setRecord("wait","0.5"));         //先停顿1秒
			list.add(setRecord("PlayBack",voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceId1))); 
		}
		
		return list;
	}
	
	public List<Record> getRespond4PlayList(BSHOrderList bshOrderList) {
		
		String voicePath = BSHCallParamConfig.getVoicePathSingle();
		List<Record> list = new ArrayList<Record>();
		
		String voiceId1 = "Respond_4";
		if(BSHVoiceConfig.getVoiceMap().containsKey(voiceId1)) {
			list.add(setRecord("wait","0.5"));         //先停顿1秒
			list.add(setRecord("PlayBack",voicePath + "/" + BSHVoiceConfig.getVoiceMap().get(voiceId1))); 
		}
		
		return list;
	}
	
	public Record setRecord(String action,String path) {
		
		Record record = new Record();
		
		record.set("action", action);
		record.set("path", path);
		
		return record;
		
	}
	
	

}
