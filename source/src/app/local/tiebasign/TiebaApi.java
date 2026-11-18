package app.local.tiebasign;

import org.json.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Protocol logic separated from Android UI and HTTP for fixture testing. */
public final class TiebaApi {
    public interface Transport { JSONObject call(String path, Map<String,String> form) throws Exception; }
    public interface Cookies { String bduss(); }
    public interface Stop { boolean requested(); }
    public static final class ApiException extends Exception {
        public final boolean loginRequired;
        public ApiException(String message) { this(message,false); }
        public ApiException(String message,boolean loginRequired) { super(message);this.loginRequired=loginRequired; }
    }
    public static final class Forum {
        public final String name, id;
        public final boolean signed, signedKnown;
        public Forum(String name, boolean signed) { this(name,"",signed); }
        public Forum(String name,String id,boolean signed) { this(name,id,signed,true); }
        public Forum(String name,String id,boolean signed,boolean signedKnown) { this.name=name;this.id=id;this.signed=signed;this.signedKnown=signedKnown; }
    }
    public static final class Session {
        public String tbs, name="百度账号", note="";
        public List<Forum> forums=new ArrayList<>();
    }
    public enum Outcome { SUCCESS, ALREADY, FAILED, STOP, UNCONFIRMED }
    public static final class Result {
        public final Outcome outcome;
        public final String message, code;
        public Result(Outcome outcome,String message) {this(outcome,message,"");}
        public Result(Outcome outcome,String message,String code) {this.outcome=outcome;this.message=message;this.code=safeCode(code);}
    }
    private final Transport transport;
    private final Cookies cookies;
    private final Stop stop;
    public TiebaApi(Transport transport,Cookies cookies,Stop stop) {this.transport=transport;this.cookies=cookies;this.stop=stop;}
    public void checkStop() throws InterruptedException { if(stop.requested() || Thread.currentThread().isInterrupted()) throw new InterruptedException(); }
    public enum LoginState { SIGNED_IN, SIGNED_OUT, UNKNOWN }
    public static LoginState loginState(JSONObject root) {
        JSONObject data=root.optJSONObject("data");
        JSONObject[] objects={data,data==null?null:data.optJSONObject("user"),data==null?null:data.optJSONObject("user_info"),root};
        for(JSONObject object:objects) {
            if(object==null || !object.has("is_login") || object.isNull("is_login"))continue;
            Object flag=object.opt("is_login");
            if(truth(flag))return LoginState.SIGNED_IN;
            if(Boolean.FALSE.equals(flag) || "0".equals(String.valueOf(flag)) || "false".equals(String.valueOf(flag)))return LoginState.SIGNED_OUT;
        }
        // The authenticated list response can omit the guest-only is_login field.
        if("0".equals(root.optString("no","")) && data!=null && !data.optString("tbs","").isEmpty() && data.has("like_forum"))return LoginState.SIGNED_IN;
        if("1".equals(root.optString("no","")) && root.optString("error","").toLowerCase(Locale.ROOT).contains("not login"))return LoginState.SIGNED_OUT;
        return LoginState.UNKNOWN;
    }
    public boolean verifyLogin() throws Exception {
        checkStop();
        JSONObject root=transport.call("/mg/o/getForumHome",null);
        if(root.has("errno") && !"0".equals(root.optString("errno")))return false;
        return loginState(root)==LoginState.SIGNED_IN;
    }
    public Session load() throws Exception {
        checkStop();
        JSONObject root=transport.call("/mo/q/newmoindex?need_follow_list=1",null);
        JSONObject data=root.optJSONObject("data");
        if(data==null) throw new ApiException("百度返回了未知格式，请稍后重试");
        LoginState state=loginState(root);
        if(state==LoginState.SIGNED_OUT) throw new ApiException("贴吧尚未确认登录。请打开百度页面完成登录，再点“返回签到”。",true);
        if(state==LoginState.UNKNOWN && !verifyLogin()) throw new ApiException("暂时无法确认贴吧登录状态；这不代表已经退出账号。请打开百度页面检查，再返回刷新。");
        if(root.has("no") && !"0".equals(root.optString("no"))) throw new ApiException("百度暂时未提供列表，请到官方页面检查登录或验证");
        Session session=new Session();
        session.tbs=data.optString("tbs","");
        if(session.tbs.isEmpty()) throw new ApiException("已确认账号，但暂未取得签到凭据；请稍后刷新，不必退出账号。");
        JSONObject user=data.optJSONObject("user");
        if(user==null) user=data.optJSONObject("user_info");
        if(user!=null) session.name=user.optString("name_show",user.optString("name",user.optString("user_name","百度账号")));
        List<Forum> web=parseWeb(data.opt("like_forum"));
        String bduss=cookies.bduss();
        if(!bduss.isEmpty()) {
            try {
                session.forums=loadAll(bduss);
                if(session.forums.isEmpty() && !web.isEmpty()) throw new ApiException("客户端列表为空");
                session.forums=mergeSignedState(session.forums,web);
                return session;
            } catch(InterruptedException e) {throw e;}
            catch(Exception e) {
                checkStop();
                if(web.isEmpty()) throw new ApiException("关注列表读取失败，请稍后刷新；没有执行签到");
                session.note="当前使用网页返回的列表，可能不包含全部关注；只签到下方列出的贴吧。";
            }
        } else session.note="当前使用网页返回的列表，可能不包含全部关注；只签到下方列出的贴吧。";
        session.forums=web;
        return session;
    }
    private List<Forum> loadAll(String bduss) throws Exception {
        LinkedHashMap<String,Forum> forums=new LinkedHashMap<>();
        for(int page=1;page<=30;page++) {
            checkStop();
            Map<String,String> params=new TreeMap<>();
            params.put("BDUSS",bduss);params.put("_client_type","2");params.put("_client_version","9.7.8.0");
            params.put("page_no",Integer.toString(page));params.put("page_size","200");
            params.put("timestamp",Long.toString(System.currentTimeMillis()));
            params.put("sign",protocolSignature(params));
            JSONObject root=transport.call("https://c.tieba.baidu.com/c/f/forum/like",params);
            if(!"0".equals(root.optString("error_code",""))) throw new ApiException("百度暂时未提供完整关注列表");
            Object raw=root.opt("forum_list");
            if(!(raw instanceof JSONObject) && !(raw instanceof JSONArray)) throw new ApiException("关注列表格式发生变化");
            List<Forum> batch=parseClient(raw);
            int before=forums.size();
            for(Forum forum:batch) forums.put(forum.name,forum);
            if(!truth(root.opt("has_more"))) return new ArrayList<>(forums.values());
            if(forums.size()==before) throw new ApiException("贴吧分页返回重复数据");
            Thread.sleep(350);
        }
        throw new ApiException("关注列表超过读取上限，未将部分结果当成完整列表");
    }
    public String prepareClientSign() throws Exception {
        checkStop();
        String bduss=cookies.bduss();
        if(bduss.isEmpty())throw new ApiException("未取得客户端登录凭据，请到百度官方页面检查登录",true);
        Map<String,String> form=new TreeMap<>();
        form.put("_client_type","2");form.put("_client_version","6.7.0");
        form.put("_phone_imei","000000000000000");form.put("bdusstoken",bduss);
        form.put("sign",protocolSignature(form));
        JSONObject response=transport.call("https://c.tieba.baidu.com/c/s/login",form);
        String code=response.optString("error_code","");
        if(!"0".equals(code))throw new ApiException("客户端登录检查未通过（代码 "+safeCode(code)+"），没有执行签到");
        JSONObject anti=response.optJSONObject("anti");
        String tbs=anti==null?"":anti.optString("tbs","");
        if(tbs.isEmpty())throw new ApiException("客户端未提供签到凭据，没有执行签到");
        return tbs;
    }
    public Result sign(String tbs,Forum forum) throws Exception {
        checkStop();
        if(!forum.id.matches("[0-9]+"))throw new ApiException("贴吧编号缺失，没有提交签到");
        if(tbs.isEmpty())throw new ApiException("客户端签到凭据缺失，没有提交签到");
        Map<String,String> form=new LinkedHashMap<>();
        form.put("fid",forum.id);form.put("kw",forum.name);form.put("tbs",tbs);
        form.put("sign",protocolSignature(form));
        return classifyClient(transport.call("https://c.tieba.baidu.com/c/c/forum/sign",form));
    }
    static String safeCode(String code) {return code.matches("-?[0-9]{1,12}")?code:"未知";}
    public static Result classifyClient(JSONObject value) {
        String code=value.optString("error_code","");
        JSONObject nested=value.optJSONObject("error");
        if(nested!=null && nested.has("errno") && !"0".equals(nested.optString("errno")))code=nested.optString("errno");
        if("160002".equals(code))return new Result(Outcome.ALREADY,"今天已签到",code);
        if("0".equals(code)) {
            JSONObject info=value.optJSONObject("user_info");
            if(info!=null && info.optInt("sign_bonus_point",0)>0)return new Result(Outcome.SUCCESS,"签到成功",code);
            return new Result(Outcome.UNCONFIRMED,"返回结果需刷新核对（代码 0）",code);
        }
        // A nonzero response is never converted to success, including the original app's 340011 branch.
        return new Result(Outcome.STOP,"客户端请求未成功（代码 "+safeCode(code)+"），已暂停",code);
    }
    public static List<Forum> mergeSignedState(List<Forum> full,List<Forum> web) {
        Map<String,Forum> states=new HashMap<>();for(Forum f:web)if(f.signedKnown)states.put(f.name,f);
        List<Forum> merged=new ArrayList<>();for(Forum f:full){Forum w=states.get(f.name);merged.add(new Forum(f.name,f.id,f.signed||(w!=null&&w.signed),f.signedKnown||w!=null));}
        return merged;
    }
    public static Result classify(JSONObject value) {
        String code=value.optString("no", "");
        if("0".equals(code)) return new Result(Outcome.SUCCESS,"签到成功");
        if("1101".equals(code)) return new Result(Outcome.ALREADY,"今天已签到");
        String message=value.optString("error",value.optString("errmsg",""));
        if("2150040".equals(code) || message.contains("验证") || message.contains("频繁") || message.contains("登录") || message.contains("登陆") || message.contains("安全") || message.contains("风控"))
            return new Result(Outcome.STOP,"百度要求验证或限制了请求，请到官方页面处理后再试");
        // Do not put server text into logs: it could contain credential-bearing URLs.
        return new Result(Outcome.FAILED,code.matches("-?\\d{1,12}") ? "签到未成功（代码 "+code+"）" : "百度返回未知结果");
    }
    static boolean truth(Object value) {return Boolean.TRUE.equals(value) || "1".equals(String.valueOf(value)) || "true".equals(String.valueOf(value));}
    public static List<Forum> parseWeb(Object value) {
        if(value instanceof JSONObject) value=((JSONObject)value).opt("list");
        LinkedHashMap<String,Forum> items=new LinkedHashMap<>();
        append(value,items);
        return new ArrayList<>(items.values());
    }
    public static List<Forum> parseClient(Object value) {
        LinkedHashMap<String,Forum> items=new LinkedHashMap<>();
        if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value;
            append(object.opt("non-gconforum"),items);append(object.opt("gconforum"),items);
        } else append(value,items);
        return new ArrayList<>(items.values());
    }
    private static void append(Object value,LinkedHashMap<String,Forum> items) {
        if(value instanceof JSONArray) {
            JSONArray array=(JSONArray)value;
            for(int i=0;i<array.length();i++) append(array.opt(i),items);
        } else if(value instanceof JSONObject) {
            JSONObject row=(JSONObject)value;
            String name=row.optString("forum_name",row.optString("name","")).trim();
            if(!name.isEmpty() && name.length()<=200) items.put(name,new Forum(name,row.optString("forum_id",row.optString("id","")),truth(row.opt("is_sign")),row.has("is_sign")&&!row.isNull("is_sign")));
        }
    }
    public static String protocolSignature(Map<String,String> params) throws Exception {
        StringBuilder input=new StringBuilder();
        for(Map.Entry<String,String> e:new TreeMap<>(params).entrySet()) if(!"sign".equals(e.getKey())) input.append(e.getKey()).append('=').append(e.getValue());
        byte[] bytes=MessageDigest.getInstance("MD5").digest(input.append("tiebaclient!!!").toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();for(byte b:bytes) hex.append(String.format(Locale.ROOT,"%02X",b&255));return hex.toString();
    }
    public static String encode(Map<String,String> form) throws Exception {
        StringBuilder result=new StringBuilder();
        for(Map.Entry<String,String> e:form.entrySet()) {
            if(result.length()>0) result.append('&');
            result.append(URLEncoder.encode(e.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(e.getValue(),"UTF-8"));
        }
        return result.toString();
    }
}
