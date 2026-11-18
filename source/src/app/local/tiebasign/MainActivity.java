package app.local.tiebasign;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int BG=0xfff6f7f3, INK=0xff203c36, MUTED=0xff6c7d77, TEAL=0xff167d6d;
    private static final String LOGIN="https://wappass.baidu.com/passport/?login&tpl=tb&u=https%3A%2F%2Ftieba.baidu.com%2F";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean cancelled=false, busy=false, destroyed=false, signing=false;
    private final HttpTransport transport=new HttpTransport(()->cancelled);
    private final TiebaApi api=new TiebaApi(transport,HttpTransport::bduss,()->cancelled);
    private TiebaApi.Session session;
    private WebView web;
    private TextView status, subtitle, count, note, empty;
    private LinearLayout list;
    private Button signButton, loginButton, refreshButton, stopButton, logoutButton;
    private Switch speedSwitch;
    private ProgressBar progress;
    private final Map<String,TextView> rowStates=new LinkedHashMap<>();
    private boolean loginScreen=false;
    private boolean listCurrent=false, checkingLogin=false, checkedThisVisit=false;
    private int loginVisit=0;
    private String pendingMessage="";
    private long runStarted;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        WebView.setWebContentsDebuggingEnabled(false);
        CookieManager.getInstance().setAcceptCookie(true);
        showHome();
        if(CookieManager.getInstance().hasCookies()) refresh(false);
    }
    private int dp(float value) {return Math.round(value*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,int radius) {GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,int size,int color,boolean bold) {
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(3),1);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private LinearLayout column() {LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private void gap(LinearLayout l,int size) {View v=new View(this);l.addView(v,new LinearLayout.LayoutParams(1,dp(size)));}
    private Button button(String title,boolean primary,Runnable action) {
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextSize(15);b.setTextColor(primary?Color.WHITE:TEAL);
        b.setBackground(shape(primary?TEAL:0xffe6eee8,14));b.setMinHeight(dp(48));b.setPadding(dp(12),dp(8),dp(12),dp(8));
        b.setOnClickListener(v->action.run());return b;
    }
    private void setScreen(View screen) {
        screen.setBackgroundColor(BG);
        screen.setOnApplyWindowInsetsListener((v,insets)-> {
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;
        });
        setContentView(screen);screen.requestApplyInsets();
    }
    private void showHome() {
        loginScreen=false;rowStates.clear();
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        LinearLayout page=column();page.setPadding(dp(22),dp(22),dp(22),dp(24));scroll.addView(page);
        LinearLayout titleRow=new LinearLayout(this);titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(text("简签贴吧",26,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView tag=text("本地 · 无广告",12,TEAL,true);tag.setPadding(dp(10),dp(6),dp(10),dp(6));tag.setBackground(shape(0xffe6eee8,30));titleRow.addView(tag);page.addView(titleRow);
        gap(page,6);page.addView(text("每天的签到，简单一点。",14,MUTED,false));gap(page,24);
        LinearLayout hero=column();hero.setPadding(dp(20),dp(20),dp(20),dp(20));hero.setBackground(shape(Color.WHITE,22));page.addView(hero);
        status=text("先连接你的百度账号",20,INK,true);hero.addView(status);gap(hero,8);
        subtitle=text("在百度官方页面登录。登录状态仅保存在这台设备。",14,MUTED,false);hero.addView(subtitle);gap(hero,18);
        loginButton=button(HttpTransport.bduss().isEmpty()?"登录百度账号":"检查百度登录",true,()->showLogin(HttpTransport.bduss().isEmpty()?LOGIN:"https://tieba.baidu.com/"));hero.addView(loginButton,new LinearLayout.LayoutParams(-1,dp(50)));
        gap(hero,12);LinearLayout actions=new LinearLayout(this);
        refreshButton=button("刷新关注列表",false,()->refresh(false));actions.addView(refreshButton,new LinearLayout.LayoutParams(0,dp(48),1));
        View spacer=new View(this);actions.addView(spacer,new LinearLayout.LayoutParams(dp(10),1));
        logoutButton=button("退出账号",false,this::logout);actions.addView(logoutButton,new LinearLayout.LayoutParams(0,dp(48),1));hero.addView(actions);
        gap(page,22);count=text("还没有读取关注列表",16,INK,true);page.addView(count);gap(page,10);
        note=text("登录后刷新列表，再点击一键签到。",13,MUTED,false);page.addView(note);gap(page,16);
        speedSwitch=new Switch(this);speedSwitch.setText("加速签到");speedSwitch.setTextSize(15);speedSwitch.setTextColor(INK);
        speedSwitch.setChecked(getPreferences(MODE_PRIVATE).getBoolean("accelerated",true));
        speedSwitch.setOnCheckedChangeListener((v,checked)->getPreferences(MODE_PRIVATE).edit().putBoolean("accelerated",checked).apply());
        page.addView(speedSwitch,new LinearLayout.LayoutParams(-1,dp(44)));gap(page,10);
        signButton=button("一键签到",true,()->refresh(true));page.addView(signButton,new LinearLayout.LayoutParams(-1,dp(56)));
        gap(page,10);progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setVisibility(View.GONE);page.addView(progress,new LinearLayout.LayoutParams(-1,dp(5)));
        gap(page,8);stopButton=button("停止签到",false,()->{cancelled=true;stopButton.setEnabled(false);note.setText("正在停止，当前请求可能已经提交；下次刷新可确认结果。");});stopButton.setVisibility(View.GONE);page.addView(stopButton,new LinearLayout.LayoutParams(-1,dp(44)));
        gap(page,16);list=column();page.addView(list);
        empty=text("关注的贴吧会显示在这里\n每个吧的签到结果都会单独列出",14,MUTED,false);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(12),dp(30),dp(12),dp(30));list.addView(empty);
        gap(page,24);Button help=button("使用说明与隐私",false,this::showHelp);page.addView(help,new LinearLayout.LayoutParams(-1,dp(46)));
        gap(page,12);TextView footer=text("个人工具 · 非百度官方应用\n0.1.4 测试版",12,MUTED,false);footer.setGravity(Gravity.CENTER);page.addView(footer);
        setScreen(scroll);if(session!=null)displaySession();if(!pendingMessage.isEmpty())note.setText(pendingMessage);setBusy(busy);
    }
    private void setBusy(boolean value) {
        busy=value;
        loginButton.setEnabled(!value);refreshButton.setEnabled(!value);logoutButton.setEnabled(!value);
        speedSwitch.setEnabled(!value);
        signButton.setEnabled(!value && listCurrent && session!=null && !session.forums.isEmpty());signButton.setAlpha(signButton.isEnabled()?1f:.45f);
        stopButton.setVisibility(value?View.VISIBLE:View.GONE);stopButton.setEnabled(true);
        progress.setVisibility(value?View.VISIBLE:View.GONE);
    }
    private void displaySession() {
        status.setText("已连接 · "+session.name);subtitle.setText("只在你点击签到时操作。请保持此页在前台。");loginButton.setText("打开百度官方页面");
        count.setText("关注的贴吧 · "+session.forums.size());note.setText(session.note.isEmpty()?"只处理尚未签到的贴吧，遇到验证会停止。":session.note);
        list.removeAllViews();rowStates.clear();
        if(session.forums.isEmpty()) {list.addView(text("未读取到关注的贴吧。若你确有关注，请稍后刷新。",14,MUTED,false));return;}
        for(TiebaApi.Forum forum:session.forums) {
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(14),dp(14),dp(14),dp(14));row.setBackground(shape(Color.WHITE,14));
            TextView name=text(forum.name+"吧",15,INK,true);row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            TextView state=text(forum.signed?"今日已签":"等待签到",12,forum.signed?TEAL:MUTED,false);state.setPadding(dp(10),0,0,0);state.setMaxWidth(dp(145));row.addView(state);rowStates.put(forum.name,state);
            list.addView(row,new LinearLayout.LayoutParams(-1,-2));gap(list,8);
        }
    }
    private void ui(Runnable action) {runOnUiThread(()->{if(!destroyed && !loginScreen)action.run();});}
    private void refresh(boolean startSigning) {
        if(busy)return;
        final int concurrency=speedSwitch.isChecked()?8:4;
        runStarted=SystemClock.elapsedRealtime();cancelled=false;listCurrent=false;pendingMessage="";signing=startSigning;setBusy(true);progress.setIndeterminate(true);note.setText(startSigning?"正在核对登录状态与关注列表…":"正在读取关注列表…");
        worker.execute(()->{
            try {
                TiebaApi.Session next=api.load();api.checkStop();
                ui(()->{session=next;listCurrent=true;displaySession();});
                if(startSigning) runSigns(next,concurrency);
            } catch(InterruptedException e) {ui(()->note.setText("已停止。下次刷新后可继续。"));}
            catch(Exception e) {
                String message=safeError(e);boolean loginRequired=e instanceof TiebaApi.ApiException && ((TiebaApi.ApiException)e).loginRequired;
                ui(()->{listCurrent=false;if(loginRequired)session=null;status.setText(loginRequired?"需要完成百度登录":"列表刷新未完成");subtitle.setText(loginRequired?"完成网页登录后，点“返回签到”。":"刷新失败不会清除账号，不需要先退出重进。");pendingMessage=message;note.setText(message);count.setText("未读取到最新关注列表");list.removeAllViews();rowStates.clear();});
            } finally {signing=false;ui(()->setBusy(false));}
        });
    }
    private void runSigns(TiebaApi.Session current,int concurrency) throws Exception {
        Set<String> initiallySigned=new HashSet<>();
        for(TiebaApi.Forum forum:current.forums)if(forum.signed)initiallySigned.add(forum.name);
        final String clientTbs=initiallySigned.size()==current.forums.size()?"":api.prepareClientSign();
        Map<String,Integer> codes=new LinkedHashMap<>();
        ui(()->{progress.setIndeterminate(false);progress.setMax(Math.max(1,current.forums.size()));progress.setProgress(0);note.setText("正在核对并签到，已签过的贴吧会直接跳过。");});
        SignQueue.Stats stats=SignQueue.run(current.forums,forum->api.sign(clientTbs,forum),()->cancelled,(forum,result,error,done)->{
            if(!forum.signed){String code=error==null?result.code:"网络或本地异常";codes.put(code,codes.containsKey(code)?codes.get(code)+1:1);}
            String message=error==null?result.message:"结果未确认";
            int color=error==null && (result.outcome==TiebaApi.Outcome.SUCCESS || result.outcome==TiebaApi.Outcome.ALREADY)?TEAL:0xffa05337;
            ui(()->{row(forum.name,message,color);progress.setProgress(done);count.setText("已处理 "+done+" / "+current.forums.size());});
        },concurrency);
        double seconds=(SystemClock.elapsedRealtime()-runStarted)/1000.0;
        int verifiedNew=0,verifiedSigned=0,verifiedCoverage=0;
        boolean verified=false;
        if(!cancelled) {
            ui(()->note.setText("请求已结束，正在重新读取服务器签到状态…"));
            try {
                TiebaApi.Session after=api.load();Map<String,Boolean> states=new HashMap<>();
                for(TiebaApi.Forum f:after.forums)if(f.signedKnown)states.put(f.name,f.signed);
                for(TiebaApi.Forum f:current.forums)if(states.containsKey(f.name)){
                    verifiedCoverage++;if(states.get(f.name)){verifiedSigned++;if(!initiallySigned.contains(f.name))verifiedNew++;}
                }
                verified=verifiedCoverage==current.forums.size() && after.note.isEmpty();
            } catch(Exception ignored) { /* A failed read never causes a sign retry. */ }
        }
        double totalSeconds=(SystemClock.elapsedRealtime()-runStarted)/1000.0;
        String summary=(stats.stopped?"已停止":"本轮处理结束")+" · 返回新签 "+stats.success+" · 已签 "+stats.already+" · 未成功 "+stats.failed+" · 未确认 "+stats.unconfirmed+" · 未处理 "+(stats.total-stats.processed)+"\n"+(concurrency==8?"加速模式":"普通模式")+" · 请求用时 "+String.format(java.util.Locale.ROOT,"%.1f",seconds)+" 秒";
        summary+=verified?"\n服务端复核：新增已签 "+verifiedNew+"，当前已签 "+verifiedSigned+" / "+current.forums.size():"\n服务端状态尚未完整确认，请稍后刷新核对";
        summary+="\n含复核用时 "+String.format(java.util.Locale.ROOT,"%.1f",totalSeconds)+" 秒";
        if(stats.failed>0 || stats.unconfirmed>0)summary+="\n返回代码统计："+codes;
        try {
            JSONObject diagnostic=new JSONObject();diagnostic.put("version","0.1.4");diagnostic.put("transport","https_client_single");diagnostic.put("concurrency",concurrency);diagnostic.put("peak_inflight",stats.peakInFlight);
            diagnostic.put("timestamp_ms",System.currentTimeMillis());diagnostic.put("total",stats.total);diagnostic.put("before_signed",initiallySigned.size());
            diagnostic.put("response_success",stats.success);diagnostic.put("already",stats.already);diagnostic.put("failed",stats.failed);diagnostic.put("unconfirmed",stats.unconfirmed);
            diagnostic.put("unprocessed",stats.total-stats.processed);diagnostic.put("stopped",stats.stopped);diagnostic.put("response_codes",new JSONObject(codes));
            diagnostic.put("request_seconds",seconds);diagnostic.put("total_seconds",totalSeconds);diagnostic.put("verification_complete",verified);
            diagnostic.put("verified_new",verifiedNew);diagnostic.put("verified_signed",verifiedSigned);diagnostic.put("verified_coverage",verifiedCoverage);
            try(java.io.OutputStream output=openFileOutput("last_run.json",MODE_PRIVATE)){output.write(diagnostic.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        }catch(Exception ignored){}
        final String finalSummary=summary;
        ui(()->{note.setText(finalSummary+(stats.stopped?"\n未确认结果请先刷新；若百度要求验证，请到官方页面处理。":""));count.setText("已处理 "+stats.processed+" / "+current.forums.size());});
    }
    private void row(String name,String value,int color) {TextView t=rowStates.get(name);if(t!=null){t.setText(value);t.setTextColor(color);}}
    private String safeError(Exception error) {
        if(error instanceof TiebaApi.ApiException)return error.getMessage();
        if(error instanceof javax.net.ssl.SSLException)return "安全连接失败，已拒绝继续。请检查网络和系统时间。";
        if(error instanceof java.net.SocketTimeoutException)return "请求超时；当前签到结果需刷新确认。";
        return "网络请求未完成，请检查连接后重试。";
    }
    public static boolean trustedPage(String raw) {
        try {Uri uri=Uri.parse(raw);String host=uri.getHost();return "https".equalsIgnoreCase(uri.getScheme()) && host!=null && (host.equals("baidu.com") || host.endsWith(".baidu.com")) && uri.getUserInfo()==null && (uri.getPort()==-1 || uri.getPort()==443);}
        catch(Exception e){return false;}
    }
    private void showLogin(String url) {
        if(busy)return;
        cancelled=false;loginScreen=true;checkedThisVisit=false;checkingLogin=false;final int visit=++loginVisit;
        LinearLayout panel=column();LinearLayout toolbar=new LinearLayout(this);toolbar.setPadding(dp(12),dp(8),dp(12),dp(8));
        Button back=button("返回签到",true,this::closeLogin);toolbar.addView(back,new LinearLayout.LayoutParams(dp(108),dp(44)));
        TextView host=text("百度官方登录",12,INK,true);host.setGravity(Gravity.CENTER);toolbar.addView(host,new LinearLayout.LayoutParams(0,dp(44),1));
        Button again=button("登录入口",false,()->{if(web!=null){checkedThisVisit=false;web.loadUrl(LOGIN);}});toolbar.addView(again,new LinearLayout.LayoutParams(dp(88),dp(44)));panel.addView(toolbar);
        TextView hint=text("此页只用于登录。看到贴吧帖子后，请点“返回签到”；不用点网页里的“打开 APP”。",13,INK,false);hint.setPadding(dp(16),dp(6),dp(16),dp(12));panel.addView(hint);
        web=new WebView(this);WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setSaveFormData(false);settings.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request) {
                String next=request.getUrl().toString();
                if(trustedPage(next))return false;
                hint.setText("不用打开百度 App。完成网页登录后，点上方或下方的“返回签到”。");return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request) {
                String scheme=request.getUrl().getScheme();
                if("https".equalsIgnoreCase(scheme))return null;
                return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onPageStarted(WebView view,String next,android.graphics.Bitmap favicon) {
                if(!trustedPage(next)) {view.stopLoading();host.setText("已阻止不安全页面");} else host.setText(Uri.parse(next).getHost());
            }
            @Override public void onPageFinished(WebView view,String page) {
                CookieManager.getInstance().flush();
                if(!trustedPage(page) || !"tieba.baidu.com".equals(Uri.parse(page).getHost()) || checkedThisVisit || checkingLogin)return;
                checkedThisVisit=true;checkingLogin=true;
                hint.setText("正在确认百度登录状态，确认成功后会自动回到签到界面…");
                worker.execute(()->{
                    boolean confirmed=false;
                    try {confirmed=api.verifyLogin();}catch(Exception ignored) {}
                    final boolean success=confirmed;
                    runOnUiThread(()->{
                        if(destroyed || !loginScreen || loginVisit!=visit)return;
                        checkingLogin=false;
                        if(success) {Toast.makeText(MainActivity.this,"登录已确认，正在返回签到",Toast.LENGTH_SHORT).show();closeLogin();}
                        else hint.setText("尚未确认贴吧登录。若网页已显示你的账号，可点“返回签到”再刷新；不用点“打开 APP”。");
                    });
                });
            }
            @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,SslError error) {handler.cancel();hint.setText("证书验证失败，已停止加载。请检查网络或系统时间。");}
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error) {if(request.isForMainFrame())hint.setText("页面加载失败。可返回后重新打开；不需要安装任何证书。");}
        });
        panel.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout bottom=column();bottom.setPadding(dp(12),dp(8),dp(12),dp(10));
        bottom.addView(button("完成登录，返回签到",true,this::closeLogin),new LinearLayout.LayoutParams(-1,dp(48)));panel.addView(bottom);
        setScreen(panel);web.loadUrl(url);
    }
    private void closeLogin() {
        ++loginVisit;if(checkingLogin)transport.disconnect();checkingLogin=false;
        CookieManager.getInstance().flush();
        if(web!=null){web.stopLoading();web.destroy();web=null;}
        CookieManager.getInstance().flush();showHome();refresh(false);
    }
    private void logout() {
        if(busy)return;
        new AlertDialog.Builder(this).setTitle("退出当前账号？").setMessage("清除此应用保存的百度登录状态和网页缓存，不影响你浏览器或百度官方 App 的登录。").setNegativeButton("取消",null).setPositiveButton("退出",(d,w)->{
            setBusy(true);
            WebStorage.getInstance().deleteAllData();WebView temporary=new WebView(this);temporary.clearCache(true);temporary.clearFormData();temporary.destroy();
            CookieManager.getInstance().removeAllCookies(done->{CookieManager.getInstance().flush();session=null;listCurrent=false;pendingMessage="";busy=false;showHome();Toast.makeText(this,"本应用的登录状态已清除",Toast.LENGTH_SHORT).show();});
        }).show();
    }
    private void showHelp() {
        new AlertDialog.Builder(this).setTitle("简单签到，账号留在本机").setMessage("1. 登录百度账号，完成百度自己的验证。\n2. 返回后查看关注列表。\n3. 点一键签到，并保持此页在前台。\n加速签到会逐步增加同时处理的数量；关闭开关使用普通模式。遇到限制会停止，不自动重复提交。\n\n遇到验证码、限制或网络异常会停止；不自动绕过验证。网页列表可能不完整时会明确提示。\n\n应用只申请联网权限，无广告 SDK、统计 SDK、自有服务器，也不会修改 DNS。登录 Cookie 由本应用的系统 WebView 本地保存，签到时只发送给百度贴吧。未实现额外的凭据加密保险库；请保持设备锁屏与系统安全。关闭系统备份和设备迁移。\n\n百度登录/验证网页由百度提供，可能含其自身的资源或推荐内容。本工具主界面与签到结果页没有广告。\n\n测试版支持 Android 8.0 及以上，单账号手动一键签到，不含后台定时任务。百度接口可能变化，签到结果以服务端复核为准；无法保证所有贴吧每次都成功。独立实现，非百度官方应用。").setPositiveButton("知道了",null).show();
    }
    @Override public void onBackPressed() {
        if(loginScreen){closeLogin();return;}
        if(busy){cancelled=true;note.setText("正在停止…");return;}
        super.onBackPressed();
    }
    @Override public void onStop() {super.onStop();if(signing)cancelled=true;CookieManager.getInstance().flush();}
    @Override public void onDestroy() {destroyed=true;cancelled=true;worker.shutdownNow();transport.disconnect();if(web!=null){web.destroy();web=null;}super.onDestroy();}
}
