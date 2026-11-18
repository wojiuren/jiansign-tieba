package app.local.tiebasign;
import android.app.*;
import android.os.Bundle;
import org.json.*;
import java.net.URL;
import java.util.*;

public class TestRunner extends Instrumentation {
    private int count=0;
    private void check(boolean condition,String name) {if(!condition)throw new AssertionError(name);count++;}
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try {
            check(TiebaApi.classify(new JSONObject("{\"no\":0}")).outcome==TiebaApi.Outcome.SUCCESS,"success numeric");
            check(TiebaApi.classify(new JSONObject("{\"no\":\"1101\"}")).outcome==TiebaApi.Outcome.ALREADY,"already string");
            check(TiebaApi.classify(new JSONObject("{\"no\":2150040}")).outcome==TiebaApi.Outcome.STOP,"captcha code");
            check(TiebaApi.classify(new JSONObject("{\"no\":4,\"error\":\"请先登录\"}")).outcome==TiebaApi.Outcome.STOP,"login expiry");
            check(TiebaApi.classify(new JSONObject("{\"no\":8,\"error\":\"操作过于频繁\"}")).outcome==TiebaApi.Outcome.STOP,"rate limit");
            check(TiebaApi.classify(new JSONObject("{}")).outcome==TiebaApi.Outcome.FAILED,"unknown response cannot succeed");
            check(!TiebaApi.classify(new JSONObject("{\"no\":42,\"error\":\"secret-cookie\"}")).message.contains("secret"),"server error redaction");
            check(TiebaApi.parseWeb(new JSONArray("[{\"forum_name\":\"甲\",\"is_sign\":1},{\"forum_name\":\"甲\",\"is_sign\":\"1\"},{\"forum_name\":\"乙\"}]")).size()==2,"web deduplication");
            check(TiebaApi.parseWeb(new JSONObject("{\"list\":[{\"forum_name\":\"甲\",\"is_sign\":true}]}")).get(0).signed,"wrapped web list");
            check(TiebaApi.parseClient(new JSONObject("{\"non-gconforum\":{\"name\":\"甲\"},\"gconforum\":[{\"name\":\"乙\"}]}")).size()==2,"client list shapes");
            check(TiebaApi.parseClient(new JSONArray("[{\"name\":\"甲\"}]")).size()==1,"client array");
            check(TiebaApi.parseWeb(new JSONArray("[{\"forum_name\":\"\"},{}]")).isEmpty(),"malformed rows");
            check(HttpTransport.allowed(new URL("https://tieba.baidu.com/sign/add")),"allowed endpoint");
            check(!HttpTransport.allowed(new URL("http://tieba.baidu.com/sign/add")),"block cleartext");
            check(!HttpTransport.allowed(new URL("https://tieba.baidu.com.attacker.invalid/sign/add")),"block lookalike");
            check(!HttpTransport.allowed(new URL("https://user@tieba.baidu.com/sign/add")),"block userinfo");
            check(!HttpTransport.allowed(new URL("https://tieba.baidu.com:444/sign/add")),"block alternate port");
            check(MainActivity.trustedPage("https://wappass.baidu.com/passport/"),"official login accepted");
            check(!MainActivity.trustedPage("https://baidu.com.evil.invalid/"),"login host boundary");
            check(!MainActivity.trustedPage("intent://login"),"native intent blocked");
            check(!MainActivity.trustedPage("http://passport.baidu.com/"),"login cleartext blocked");
            Map<String,String> params=new LinkedHashMap<>();params.put("kw","甲 &乙");
            check(TiebaApi.encode(params).equals("kw=%E7%94%B2+%26%E4%B9%99"),"form encoding");
            Map<String,String> a=new LinkedHashMap<>(),b=new LinkedHashMap<>();a.put("b","2");a.put("a","1");b.put("a","1");b.put("b","2");
            check(TiebaApi.protocolSignature(a).equals(TiebaApi.protocolSignature(b)),"protocol ordering");
            final int[] calls={0};
            TiebaApi offline=new TiebaApi((path,form)->{calls[0]++;throw new AssertionError("network called after cancel");},()->"",()->true);
            try{offline.load();throw new AssertionError("cancel ignored");}catch(InterruptedException expected){check(calls[0]==0,"cancel before network");}
            TiebaApi guest=new TiebaApi((path,form)->new JSONObject("{\"no\":1,\"data\":{\"is_login\":false}}"),()->"",()->false);
            try{guest.load();throw new AssertionError("guest accepted");}catch(TiebaApi.ApiException expected){check(true,"guest rejected");}
            JSONObject home=new JSONObject("{\"no\":0,\"data\":{\"is_login\":true,\"tbs\":\"fixture-token\",\"like_forum\":[{\"forum_name\":\"网页\"}]}}");
            TiebaApi fallback=new TiebaApi((path,form)->home,()->"",()->false);
            check(fallback.load().note.contains("不包含全部"),"partial list disclosed");
            final int[] page={0};
            TiebaApi paging=new TiebaApi((path,form)->{
                if(form==null)return home;
                page[0]++;check(form.get("page_no").equals(Integer.toString(page[0])),"page number "+page[0]);
                return new JSONObject(page[0]==1?"{\"error_code\":\"0\",\"has_more\":1,\"forum_list\":{\"non-gconforum\":[{\"name\":\"甲\"}]}}":"{\"error_code\":0,\"has_more\":0,\"forum_list\":{\"gconforum\":[{\"name\":\"甲\"},{\"name\":\"乙\"}]}}");
            },()->"fixture-only",()->false);
            TiebaApi.Session complete=paging.load();check(complete.forums.size()==2 && complete.note.isEmpty() && page[0]==2,"full pagination deduplicated");
            TiebaApi repeat=new TiebaApi((path,form)->form==null?home:new JSONObject("{\"error_code\":0,\"has_more\":1,\"forum_list\":[{\"name\":\"重复\"}]}"),()->"fixture-only",()->false);
            TiebaApi.Session repeated=repeat.load();check(!repeated.note.isEmpty() && repeated.forums.get(0).name.equals("网页"),"repeated pagination safe fallback");
            TiebaApi signer=new TiebaApi((path,form)->{check(path.equals("https://c.tieba.baidu.com/c/c/forum/sign") && form.get("fid").equals("123") && form.get("kw").equals("甲") && form.get("tbs").equals("test") && form.get("sign").equals(TiebaApi.protocolSignature(form)),"client sign payload");return new JSONObject("{\"error_code\":0,\"user_info\":{\"sign_bonus_point\":8}}");},()->"",()->false);
            check(signer.sign("test",new TiebaApi.Forum("甲","123",false)).outcome==TiebaApi.Outcome.SUCCESS,"sign response");
            JSONObject noFlag=new JSONObject("{\"no\":0,\"data\":{\"tbs\":\"fixture-token\",\"like_forum\":[{\"forum_name\":\"字段兼容\"}]}}");
            check(TiebaApi.loginState(noFlag)==TiebaApi.LoginState.SIGNED_IN,"missing guest-only flag accepted with authenticated list");
            check(new TiebaApi((path,form)->noFlag,()->"",()->false).load().forums.get(0).name.equals("字段兼容"),"regression: successful list without is_login is usable");
            check(TiebaApi.loginState(new JSONObject("{}"))==TiebaApi.LoginState.UNKNOWN,"missing flag is unknown, not logged out");
            check(TiebaApi.loginState(new JSONObject("{\"data\":{\"user_info\":{\"is_login\":1}}}"))==TiebaApi.LoginState.SIGNED_IN,"nested profile login flag");
            check(TiebaApi.loginState(new JSONObject("{\"data\":{\"user\":{\"is_login\":\"1\"}}}"))==TiebaApi.LoginState.SIGNED_IN,"nested user login flag");
            check(TiebaApi.loginState(new JSONObject("{\"data\":{\"is_login\":false,\"tbs\":\"guest-token\"}}"))==TiebaApi.LoginState.SIGNED_OUT,"guest token is not login proof");
            check(TiebaApi.loginState(new JSONObject("{\"no\":0,\"data\":{\"is_login\":false,\"tbs\":\"guest-token\",\"like_forum\":[]}}"))==TiebaApi.LoginState.SIGNED_OUT,"explicit logout not overridden by list");
            check(new TiebaApi((path,form)->new JSONObject("{\"errno\":0,\"data\":{\"user_info\":{\"is_login\":1}}}"),()->"",()->false).verifyLogin(),"profile confirms auto-return");
            check(!new TiebaApi((path,form)->new JSONObject("{\"errno\":0,\"data\":{\"user_info\":{\"is_login\":0}}}"),()->"",()->false).verifyLogin(),"guest profile cannot trigger auto-return");
            check(!new TiebaApi((path,form)->new JSONObject("{\"errno\":4,\"data\":{\"user_info\":{\"is_login\":1}}}"),()->"",()->false).verifyLogin(),"failed profile cannot confirm login");
            try{guest.load();throw new AssertionError("guest accepted");}catch(TiebaApi.ApiException expected){check(expected.loginRequired,"real guest gets login-required classification");}
            TiebaApi unknown=new TiebaApi((path,form)->new JSONObject("{\"data\":{}}"),()->"",()->false);
            try{unknown.load();throw new AssertionError("unknown accepted");}catch(TiebaApi.ApiException expected){check(!expected.loginRequired,"unknown response must not demand logout");}
            List<TiebaApi.Forum> merged=TiebaApi.mergeSignedState(Arrays.asList(new TiebaApi.Forum("甲",false),new TiebaApi.Forum("乙",false)),Arrays.asList(new TiebaApi.Forum("甲",true),new TiebaApi.Forum("不在完整列表",true)));
            check(merged.size()==2 && merged.get(0).signed && !merged.get(1).signed,"merge web status without adding unrelated forums");
            java.util.concurrent.atomic.AtomicInteger called=new java.util.concurrent.atomic.AtomicInteger();
            SignQueue.Stats skip=SignQueue.run(Arrays.asList(new TiebaApi.Forum("已签",true)),f->{called.incrementAndGet();throw new AssertionError("already signed must not request");},()->false,(f,r,e,n)->{});
            check(called.get()==0 && skip.already==1 && skip.processed==1,"all-signed causes zero sign requests");
            List<TiebaApi.Forum> tasks=new ArrayList<>();for(int i=0;i<8;i++)tasks.add(new TiebaApi.Forum("f"+i,i>=6));
            java.util.concurrent.atomic.AtomicInteger active=new java.util.concurrent.atomic.AtomicInteger(),peak=new java.util.concurrent.atomic.AtomicInteger();
            SignQueue.Stats fast=SignQueue.run(tasks,f->{int n=active.incrementAndGet();peak.accumulateAndGet(n,Math::max);called.incrementAndGet();try{Thread.sleep(20);return new TiebaApi.Result(TiebaApi.Outcome.SUCCESS,"ok");}finally{active.decrementAndGet();}},()->false,(f,r,e,n)->{});
            check(peak.get()<=4 && active.get()==0,"bounded concurrency");
            check(fast.success==6 && fast.already==2 && fast.processed==8 && called.get()==6,"exactly-once processing and skip counts");
            called.set(0);
            SignQueue.Stats halt=SignQueue.run(tasks,f->{called.incrementAndGet();if(f.name.equals("f0"))return new TiebaApi.Result(TiebaApi.Outcome.STOP,"verify");Thread.sleep(60);return new TiebaApi.Result(TiebaApi.Outcome.SUCCESS,"ok");},()->false,(f,r,e,n)->{});
            check(halt.stopped && called.get()<=4 && halt.failed==1,"verification halts queue; drains already in-flight requests");
            called.set(0);
            SignQueue.Stats lost=SignQueue.run(tasks,f->{called.incrementAndGet();if(f.name.equals("f0"))throw new java.net.SocketTimeoutException();Thread.sleep(60);return new TiebaApi.Result(TiebaApi.Outcome.SUCCESS,"ok");},()->false,(f,r,e,n)->{});
            check(lost.stopped && called.get()<=4 && lost.unconfirmed==1,"uncertain timeout not retried or called a success");
            called.set(0);
            SignQueue.Stats cancelled=SignQueue.run(tasks,f->{called.incrementAndGet();return new TiebaApi.Result(TiebaApi.Outcome.SUCCESS,"ok");},()->true,(f,r,e,n)->{});
            check(cancelled.stopped && called.get()==0,"cancelled run sends no requests");
            java.util.concurrent.atomic.AtomicBoolean cancelMid=new java.util.concurrent.atomic.AtomicBoolean();called.set(0);
            SignQueue.Stats mid=SignQueue.run(tasks,f->{called.incrementAndGet();Thread.sleep(20);return new TiebaApi.Result(TiebaApi.Outcome.SUCCESS,"ok");},cancelMid::get,(f,r,e,n)->{if(r.outcome==TiebaApi.Outcome.SUCCESS)cancelMid.set(true);});
            check(mid.stopped && called.get()<=4,"mid-run cancellation limits outstanding work");
            check(TiebaApi.classifyClient(new JSONObject("{\"error_code\":0,\"user_info\":{\"sign_bonus_point\":8}}" )).outcome==TiebaApi.Outcome.SUCCESS,"client success evidence");
            check(TiebaApi.classifyClient(new JSONObject("{\"error_code\":160002}" )).outcome==TiebaApi.Outcome.ALREADY,"client already is not a new sign");
            TiebaApi.Result limited=TiebaApi.classifyClient(new JSONObject("{\"error_code\":340011}"));
            check(limited.outcome==TiebaApi.Outcome.STOP && limited.code.equals("340011") && limited.message.contains("340011"),"original app's extra success code must halt and remain diagnosable");
            check(TiebaApi.classifyClient(new JSONObject("{\"error_code\":0,\"error\":{\"errno\":320022}}" )).outcome==TiebaApi.Outcome.STOP,"nested error overrides outer zero");
            check(TiebaApi.classifyClient(new JSONObject("{\"error_code\":0}" )).outcome==TiebaApi.Outcome.UNCONFIRMED,"missing success evidence requires verification");
            TiebaApi.Result redacted=TiebaApi.classifyClient(new JSONObject("{\"error_code\":\"secret-cookie\",\"error_msg\":\"secret-cookie\"}"));
            check(!redacted.message.contains("secret") && !redacted.code.contains("secret"),"client diagnostic redacts untrusted values");
            List<TiebaApi.Forum> withIds=TiebaApi.mergeSignedState(TiebaApi.parseClient(new JSONArray("[{\"id\":123,\"name\":\"甲\"}]")),TiebaApi.parseWeb(new JSONArray("[{\"forum_id\":123,\"forum_name\":\"甲\",\"is_sign\":0}]")));
            check(withIds.get(0).id.equals("123") && withIds.get(0).signedKnown && !withIds.get(0).signed,"preserve id and explicit unsigned status");
            check(!TiebaApi.parseClient(new JSONArray("[{\"id\":123,\"name\":\"甲\"}]")).get(0).signedKnown,"missing status cannot be used as authoritative verification");
            final int[] prepareCalls={0};
            TiebaApi nativeApi=new TiebaApi((path,form)->{prepareCalls[0]++;check(path.equals("https://c.tieba.baidu.com/c/s/login") && form.get("bdusstoken").equals("fixture-only") && form.get("sign").equals(TiebaApi.protocolSignature(form)),"client login request contract");return new JSONObject("{\"error_code\":0,\"anti\":{\"tbs\":\"client-fixture-tbs\"}}");},()->"fixture-only",()->false);
            check(nativeApi.prepareClientSign().equals("client-fixture-tbs") && prepareCalls[0]==1,"reuse client-issued tbs");
            try{nativeApi.sign("tbs",new TiebaApi.Forum("missing-id",false));throw new AssertionError("missing id submitted");}catch(TiebaApi.ApiException expected){check(prepareCalls[0]==1,"missing id causes zero sign requests");}
            called.set(0);
            SignQueue.Stats uncertain=SignQueue.run(tasks,f->{called.incrementAndGet();return new TiebaApi.Result(TiebaApi.Outcome.UNCONFIRMED,"verify","0");},()->false,(f,r,e,n)->{});
            check(uncertain.stopped && uncertain.success==0 && uncertain.unconfirmed>0 && called.get()<=4,"ambiguous acknowledgement stops without retry");
            java.util.concurrent.CountDownLatch four=new java.util.concurrent.CountDownLatch(4);
            SignQueue.Stats fourWay=SignQueue.run(tasks,f->{four.countDown();if(!four.await(2,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("four requests never became in-flight together");return new TiebaApi.Result(TiebaApi.Outcome.SUCCESS,"ok");},()->false,(f,r,e,n)->{});
            check(fourWay.success==6 && fourWay.unconfirmed==0,"four concurrent network tasks actually start");
            List<TiebaApi.Forum> many=new ArrayList<>();for(int i=0;i<32;i++)many.add(new TiebaApi.Forum("q"+i,false));
            java.util.concurrent.CountDownLatch eight=new java.util.concurrent.CountDownLatch(8);active.set(0);peak.set(0);called.set(0);
            SignQueue.Stats accelerated=SignQueue.run(many,f->{int index=Integer.parseInt(f.name.substring(1));int n=active.incrementAndGet();peak.accumulateAndGet(n,Math::max);called.incrementAndGet();try{if(index>=8){eight.countDown();if(!eight.await(2,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("eight workers did not start");}return new TiebaApi.Result(TiebaApi.Outcome.SUCCESS,"ok","0");}finally{active.decrementAndGet();}},()->false,(f,r,e,n)->{},8);
            check(accelerated.success==32 && called.get()==32 && peak.get()==8 && accelerated.peakInFlight==8,"accelerated queue grows to eight and executes each item once");
            called.set(0);
            SignQueue.Stats earlyLimit=SignQueue.run(many,f->{called.incrementAndGet();return new TiebaApi.Result(TiebaApi.Outcome.STOP,"limited","340011");},()->false,(f,r,e,n)->{},8);
            check(earlyLimit.stopped && called.get()<=4 && earlyLimit.success==0,"restriction during initial wave prevents acceleration");
            called.set(0);java.util.concurrent.atomic.AtomicInteger afterLimit=new java.util.concurrent.atomic.AtomicInteger();
            SignQueue.Stats lateLimit=SignQueue.run(many,f->{int index=Integer.parseInt(f.name.substring(1));called.incrementAndGet();if(index==8){afterLimit.incrementAndGet();return new TiebaApi.Result(TiebaApi.Outcome.STOP,"limited","340011");}if(index>8)Thread.sleep(30);return new TiebaApi.Result(TiebaApi.Outcome.SUCCESS,"ok","0");},()->false,(f,r,e,n)->{},8);
            check(lateLimit.stopped && afterLimit.get()==1 && called.get()<=16 && lateLimit.failed==1,"restriction after acceleration drains at most eight outstanding requests, without retry");
            called.set(0);
            SignQueue.Stats acceleratedSkip=SignQueue.run(Arrays.asList(new TiebaApi.Forum("already",true)),f->{called.incrementAndGet();throw new AssertionError("already signed requested");},()->false,(f,r,e,n)->{},8);
            check(acceleratedSkip.already==1 && called.get()==0 && acceleratedSkip.peakInFlight==0,"accelerated already-signed run sends nothing");
            try{SignQueue.run(many,f->new TiebaApi.Result(TiebaApi.Outcome.SUCCESS,"ok"),()->false,(f,r,e,n)->{},100);throw new AssertionError("unbounded concurrency accepted");}catch(IllegalArgumentException expected){check(true,"unsupported concurrency rejected before scheduling");}
            result.putString("stream","PASS: "+count+" assertions. Mock data only; no account login or real sign-in.\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable error){result.putString("stream","FAIL after "+count+": "+error.toString());finish(Activity.RESULT_CANCELED,result);}
    }
}
