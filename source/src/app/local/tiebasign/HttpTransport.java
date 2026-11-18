package app.local.tiebasign;

import android.webkit.CookieManager;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class HttpTransport implements TiebaApi.Transport {
    public static final String BASE="https://tieba.baidu.com";
    public static final String UA="Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private final TiebaApi.Stop stop;
    private final Set<HttpsURLConnection> active=Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<HttpsURLConnection,Boolean>());
    public HttpTransport(TiebaApi.Stop stop) {this.stop=stop;}
    public static boolean allowed(URL url) {
        return "https".equalsIgnoreCase(url.getProtocol()) && (url.getPort()==-1 || url.getPort()==443) && url.getUserInfo()==null &&
                ("tieba.baidu.com".equalsIgnoreCase(url.getHost()) || "c.tieba.baidu.com".equalsIgnoreCase(url.getHost()));
    }
    public JSONObject call(String path,Map<String,String> form) throws Exception {
        URL url=new URL(path.startsWith("https://")?path:BASE+path);
        for(int hop=0;hop<4;hop++) {
            if(stop.requested() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if(!allowed(url)) throw new TiebaApi.ApiException("已阻止不安全或非贴吧的账号请求");
            HttpsURLConnection connection=(HttpsURLConnection)url.openConnection();active.add(connection);
            try {
                connection.setConnectTimeout(12000);connection.setReadTimeout(18000);connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent",UA);connection.setRequestProperty("Accept","application/json");
                connection.setRequestProperty("Referer",BASE+"/");connection.setRequestProperty("Origin",BASE);
                String cookies=CookieManager.getInstance().getCookie(url.toString());
                if(cookies!=null) connection.setRequestProperty("Cookie",cookies);
                if(form!=null) {
                    connection.setRequestMethod("POST");connection.setDoOutput(true);
                    byte[] body=TiebaApi.encode(form).getBytes(StandardCharsets.UTF_8);
                    connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
                    connection.setFixedLengthStreamingMode(body.length);
                    try(OutputStream output=connection.getOutputStream()) {output.write(body);}
                }
                int status=connection.getResponseCode();
                for(Map.Entry<String,List<String>> header:connection.getHeaderFields().entrySet())
                    if("Set-Cookie".equalsIgnoreCase(header.getKey()) && header.getValue()!=null)
                        for(String cookie:header.getValue()) CookieManager.getInstance().setCookie(url.toString(),cookie);
                if(status>=300 && status<400) {
                    if(form!=null) throw new TiebaApi.ApiException("请求被重定向，已停止；请重新检查登录状态");
                    String location=connection.getHeaderField("Location");
                    if(location==null) throw new TiebaApi.ApiException("百度返回异常跳转");
                    URL next=new URL(url,location);
                    if(!allowed(next)) throw new TiebaApi.ApiException("百度要求非加密或非贴吧跳转，已阻止；请稍后重试");
                    url=next;continue;
                }
                if(status==401 || status==403 || status==429) throw new TiebaApi.ApiException("百度限制了请求，请稍后重试或在官方页面完成验证");
                if(status!=200) throw new TiebaApi.ApiException("百度服务暂不可用（HTTP "+status+"）");
                try(InputStream input=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                    byte[] buffer=new byte[8192];int size;
                    while((size=input.read(buffer))!=-1) {
                        if(stop.requested()) throw new InterruptedException();
                        if(bytes.size()+size>4*1024*1024) throw new TiebaApi.ApiException("响应过大，已停止读取");
                        bytes.write(buffer,0,size);
                    }
                    try {return new JSONObject(bytes.toString("UTF-8"));}
                    catch(org.json.JSONException e) {throw new TiebaApi.ApiException("百度返回了网页或验证页面，请重新登录或稍后重试");}
                }
            } finally {connection.disconnect();active.remove(connection);}
        }
        throw new TiebaApi.ApiException("百度跳转次数过多，请稍后重试");
    }
    public void disconnect() {for(HttpsURLConnection connection:active)connection.disconnect();}
    public static String bduss() {
        String cookies=CookieManager.getInstance().getCookie(BASE);
        if(cookies==null)return "";
        for(String part:cookies.split(";")) {
            int pos=part.indexOf('=');if(pos>0 && "BDUSS".equals(part.substring(0,pos).trim())) return part.substring(pos+1).trim();
        }
        return "";
    }
}
