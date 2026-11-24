package com.sunwin.deepseekharness;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.FileProvider;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static com.sunwin.deepseekharness.Appearance.*;

public final class MainActivity extends Activity {
    private static final int PICK=20, CAMERA=21, EXPORT=22, NOTIFICATIONS=24;
    private HarnessApp app;
    private Appearance a;
    private final ExecutorService files=Executors.newSingleThreadExecutor();
    private final android.util.LruCache<String,Bitmap> thumbs=new android.util.LruCache<>(10);
    private long chatId, photoChat;
    private String screen="chat", draft="", photo, cameraPath;
    private boolean openLogin, visible;
    private LinearLayout root, content, thread, intro, attachment;
    private ScrollView scroll;
    private TextView header, subtitle;
    private EditText input;
    private ImageButton send;
    private final HashMap<Long,TextView> bubbles=new HashMap<>();
    private String introKey="";
    private android.content.SharedPreferences preferences() { return getSharedPreferences("ui",MODE_PRIVATE); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        app=(HarnessApp)getApplication(); a=new Appearance(this);
        chatId=preferences().getLong("chat",0);
        if (app.history.chat(chatId)==null) {
            List<History.Chat> items=app.history.list();
            choose(items.isEmpty()?app.history.create():items.get(0).id);
        }
        if (state!=null) {
            screen=state.getString("screen","chat"); draft=state.getString("draft","");
            photo=state.getString("photo"); cameraPath=state.getString("camera"); photoChat=state.getLong("photoChat");
            openLogin=state.getBoolean("launch");
        }
        startRuntime(); draw();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        // Window focus returns after the notification dialog. Never stack two
        // system prompts or repeat either one after refusal/activity recreation.
        if (!focused || !visible || isFinishing()) return;
        if (Build.VERSION.SDK_INT>=33 && !preferences().getBoolean("notificationAsked",false)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            preferences().edit().putBoolean("notificationAsked",true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATIONS);
        } else if (!preferences().getBoolean("batteryAsked",false)
                && !getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) {
            preferences().edit().putBoolean("batteryAsked",true).apply();
            Intent request=new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName()));
            try { startActivity(request); } catch (ActivityNotFoundException|SecurityException ignored) { }
        }
    }
    private void startRuntime() { startForegroundService(new Intent(this,HarnessService.class)); }
    @Override public void onResume() {
        super.onResume(); visible=true; app.listen(this::update); app.account.refresh(false); update();
    }
    @Override public void onPause() { visible=false; app.listen(null); super.onPause(); }
    @Override public void onDestroy() { files.shutdown(); super.onDestroy(); }
    @Override public void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        b.putString("screen",screen); b.putString("draft",draft); b.putString("photo",photo);
        b.putString("camera",cameraPath); b.putLong("photoChat",photoChat); b.putBoolean("launch",openLogin);
    }
    @Override public void onBackPressed() {
        if (!"chat".equals(screen)) go("chat"); else super.onBackPressed();
    }
    private void choose(long id) { chatId=id; preferences().edit().putLong("chat",id).apply(); }
    private void selectChat(long id) {
        if (id!=chatId) { discardPhoto(); draft=""; choose(id); }
        go("chat");
    }
    private void go(String page) {
        if (input!=null) getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(),0);
        screen=page; draw();
    }
    private void draw() {
        input=null; send=null; introKey=""; bubbles.clear();
        root=a.column(); root.setBackgroundColor(PAPER); root.setFocusableInTouchMode(true);
        setContentView(root);
        boolean chat="chat".equals(screen);
        LinearLayout bar=a.row(); a.pad(bar,8,4); root.addView(bar,a.size(-1,62));
        bar.addView(a.icon(chat?R.drawable.ic_history:R.drawable.ic_back,chat?"历史对话":"返回",()->go(chat?"history":"chat")),a.size(48,48));
        LinearLayout titles=a.column(); titles.setGravity(Gravity.CENTER_VERTICAL); a.pad(titles,6,0);
        header=a.text("",18,INK,true); header.setSingleLine(true); header.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(header); subtitle=a.text("",12,DIM,false); titles.addView(subtitle);
        bar.addView(titles,new LinearLayout.LayoutParams(0,-1,1));
        if (chat) bar.addView(a.icon(R.drawable.ic_settings,"设置",()->go("settings")),a.size(48,48));
        if ("history".equals(screen)) bar.addView(a.icon(R.drawable.ic_plus,"新建对话",()->selectChat(app.history.create())),a.size(48,48));
        View rule=new View(this); rule.setBackgroundColor(LINE); root.addView(rule,new LinearLayout.LayoutParams(-1,1));
        content=a.column(); root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        if (chat) buildChat();
        update();
    }
    private void update() {
        if (root==null || isFinishing()) return;
        if (openLogin && app.account.authorize!=null && visible && app.account.pending()) {
            openLogin=false; launchAuthorization(false);
        } else if (!app.account.loading && !app.account.pending() && !"".equals(app.account.phase)) openLogin=false;
        subtitle.setVisibility(View.GONE);
        switch (screen) {
            case "history": header.setText("历史对话"); showHistory(); break;
            case "settings": header.setText("设置"); showSettings(); break;
            default:
                History.Chat chat=app.history.chat(chatId); header.setText(chat.title);
                Loopback api=app.api;
                subtitle.setText(api!=null && app.account.stored ? app.account.modelStatus(api) : status());
                subtitle.setVisibility(View.VISIBLE);
                renderMessages(); updateSend();
        }
    }
    private String status() {
        switch (app.serviceState) {
            case "starting": return "正在启动";
            case "failed": return app.serviceFailure.isEmpty()?"服务启动失败":app.serviceFailure;
            case "stopped": return "服务已停止";
            default: return app.account.label();
        }
    }
    private void buildChat() {
        scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout canvas=a.column(); a.pad(canvas,20,14); scroll.addView(canvas);
        intro=a.column(); intro.setGravity(Gravity.CENTER); canvas.addView(intro,new LinearLayout.LayoutParams(-1,0,1));
        thread=a.column(); canvas.addView(thread,a.size(-1,-2));
        LinearLayout composer=a.column(); a.pad(composer,8,6); composer.setBackground(a.shape(WHITE,22,LINE));
        LinearLayout.LayoutParams box=a.size(-1,-2); box.setMargins(a.dp(14),a.dp(4),a.dp(14),a.dp(12)); content.addView(composer,box);
        attachment=a.row(); composer.addView(attachment); drawAttachment();
        input=new EditText(this); input.setTextSize(17); input.setTextColor(INK); input.setHintTextColor(DIM);
        input.setHint("输入消息"); input.setBackgroundColor(WHITE); a.pad(input,10,7);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMaxLines(5); input.setMinHeight(a.dp(48)); input.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
        input.setText(draft); input.setSelection(input.length()); composer.addView(input,a.size(-1,-2));
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int st,int count,int after) { }
            public void onTextChanged(CharSequence s,int st,int before,int count) { draft=s.toString(); updateSend(); }
            public void afterTextChanged(Editable e) { }
        });
        LinearLayout tools=a.row(); composer.addView(tools);
        ImageButton gallery=a.icon(R.drawable.ic_gallery,"相册",this::gallery), camera=a.icon(R.drawable.ic_camera,"拍照",this::camera);
        for (ImageButton b:new ImageButton[]{gallery,camera}) { a.buttonStyle(b,WHITE,12); tools.addView(b,a.size(48,48)); }
        tools.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        send=a.icon(R.drawable.ic_send,"发送",this::send); a.buttonStyle(send,BLUE,15); send.setColorFilter(WHITE);
        tools.addView(send,a.size(48,46));
    }
    private void renderMessages() {
        List<History.Message> rows=app.history.messages(chatId);
        intro.setVisibility(rows.isEmpty()?View.VISIBLE:View.GONE);
        if (rows.isEmpty()) {
            String key=status()+"|"+app.account.stored;
            if (!key.equals(introKey)) {
                introKey=key; intro.removeAllViews();
                ImageView mark=new ImageView(this); mark.setImageResource(R.drawable.app_icon); intro.addView(mark,a.size(58,58));
                a.gap(intro,16); TextView title=a.text("DeepSeek 助手",27,INK,true);
                title.setGravity(Gravity.CENTER); intro.addView(title);
                if (!app.account.stored || app.api==null) {
                    a.gap(intro,20);
                    boolean retry=retryService();
                    TextView b=a.button(retry?"重新启动":app.account.pending()?"继续登录":"登录 DeepSeek",false,
                            ()->{ if (retry) startRuntime(); else login(); });
                    b.setEnabled(retry || app.api!=null); b.setAlpha(b.isEnabled()?1f:.5f); intro.addView(b,a.size(-2,44));
                }
            }
            return;
        }
        boolean bottom=scroll.getScrollY()+scroll.getHeight()>=thread.getHeight()-a.dp(64);
        for (History.Message m:rows) {
            TextView body=bubbles.get(m.id);
            if (body==null) {
                boolean user="user".equals(m.role);
                LinearLayout group=a.column(); group.setGravity(user?Gravity.END:Gravity.START);
                LinearLayout.LayoutParams gp=a.size(-1,-2); gp.bottomMargin=a.dp(16);
                if (user) gp.leftMargin=a.dp(34); else gp.rightMargin=a.dp(18); thread.addView(group,gp);
                if (!user) { group.addView(a.text("DeepSeek",12,DIM,true)); a.gap(group,5); }
                if (m.photo!=null) {
                    ImageView img=preview(m.photo); group.addView(img,a.size(148,116)); a.gap(group,4);
                }
                body=a.text("",16,INK,false); body.setTextIsSelectable(true);
                if (user) { body.setBackground(a.shape(PALE,17,0)); a.pad(body,14,11); }
                else a.pad(body,0,3);
                group.addView(body,a.size(-2,-2)); bubbles.put(m.id,body);
            }
            String text=m.text;
            if ("pending".equals(m.state) && text.isEmpty()) text="正在回复…";
            if ("interrupted".equals(m.state)) {
                String reason=TextUtils.isEmpty(m.error)?"回复已中断":m.error;
                text=text.isEmpty()?reason:text+"\n（"+reason+"）";
            }
            body.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE);
            if (!TextUtils.equals(text,body.getText())) body.setText(text);
        }
        if (bottom) scroll.post(()->scroll.scrollTo(0,thread.getBottom()));
    }
    private void updateSend() {
        if (send==null) return;
        boolean can=!app.chat.busy && (!draft.trim().isEmpty() || photo!=null);
        send.setEnabled(can); send.setAlpha(can?1f:.44f);
    }
    private void send() {
        if (app.api==null) { toast(status()); if (retryService()) startRuntime(); return; }
        if (!app.account.stored) { login(); return; }
        if (app.account.selected.isEmpty()) {
            toast(app.account.modelError.isEmpty() ? "正在获取可用模型" : app.account.modelError);
            app.account.refresh(true); return;
        }
        if (app.chat.send(chatId,draft.trim(),photo)) { photo=null; input.setText(""); drawAttachment(); update(); }
    }
    private boolean retryService() { return "failed".equals(app.serviceState)||"stopped".equals(app.serviceState); }

    private LinearLayout page() {
        content.removeAllViews();
        ScrollView s=new ScrollView(this); content.addView(s,a.size(-1,-1));
        LinearLayout page=a.column(); a.pad(page,22,22); s.addView(page); return page;
    }
    private void groupName(LinearLayout page,String name) { page.addView(a.text(name,13,DIM,true)); a.gap(page,9); }
    private LinearLayout card(LinearLayout page) {
        LinearLayout card=a.column(); card.setBackground(a.shape(WHITE,18,LINE)); a.pad(card,4,3); page.addView(card,a.size(-1,-2)); return card;
    }
    private void row(LinearLayout card,String left,String right,Runnable action) {
        LinearLayout line=a.row(); a.pad(line,14,11);
        TextView text=a.text(left,16,INK,false); line.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        if (!right.isEmpty()) line.addView(a.text(right,14,DIM,false));
        if (action!=null) {
            ImageView next=new ImageView(this); next.setImageResource(R.drawable.ic_chevron); next.setColorFilter(DIM);
            line.addView(next,a.size(21,21)); a.buttonStyle(line,WHITE,13); line.setOnClickListener(v->action.run());
        }
        line.setMinimumHeight(a.dp(52)); card.addView(line,a.size(-1,-2));
    }
    private void showHistory() {
        LinearLayout page=page(), list=card(page);
        for (History.Chat chat:app.history.list()) row(list,chat.title,chat.id==chatId?"当前":"",()->selectChat(chat.id));
    }
    private void showSettings() {
        LinearLayout page=page(); groupName(page,"DeepSeek 账号"); LinearLayout account=card(page);
        row(account,"登录状态",status(),null);
        if (retryService()) row(account,"重新启动","",this::startRuntime);
        if (app.api!=null) {
            if (app.account.stored) row(account,"退出登录","",()->new AlertDialog.Builder(this).setMessage("退出当前账号？")
                    .setNegativeButton("取消",null).setPositiveButton("退出",(d,w)->app.account.signOut()).show());
            else if (app.account.pending()) {
                if (app.account.authorize!=null) {
                    row(account,"继续登录","",()->launchAuthorization(false));
                    row(account,"备用登录","",()->launchAuthorization(true));
                }
                row(account,"取消登录","",()->{openLogin=false; app.account.cancel();});
            } else row(account,"登录 DeepSeek","",this::login);
        }
        if (!app.account.balance.isEmpty()) row(account,"余额",app.account.balance,null);
        row(account,"充值","",()->openExternal(Uri.parse("https://platform.deepseek.com/top_up")));
        Loopback api=app.api;
        if (api!=null && app.account.stored && !app.account.modelError.isEmpty())
            row(account,"模型",app.account.modelError,()->app.account.refresh(true));
        if (api!=null && !app.account.models.isEmpty()) {
            a.gap(page,24); groupName(page,api.listing.label); LinearLayout models=card(page);
            for (String name:app.account.models) row(models,name,name.equals(app.account.selected)?"已选择":"",
                    ()->{ app.account.selected=name; app.changed(); });
        }
        a.gap(page,28); LinearLayout other=card(page); row(other,"导出诊断日志","",this::exportLog);
    }
    private void login() {
        if (app.account.pending() && app.account.authorize!=null) { launchAuthorization(false); return; }
        openLogin=true; app.account.signIn();
    }
    private void launchAuthorization(boolean tab) {
        java.net.URI url=app.account.authorize;
        if (url==null) return;
        Uri u=Uri.parse(url.toASCIIString());
        if (!tab) {
            try { startActivity(new Intent(Intent.ACTION_VIEW,u).addCategory(Intent.CATEGORY_BROWSABLE)); return; }
            catch (ActivityNotFoundException ignored) { }
        }
        try {
            CustomTabsIntent launch=new CustomTabsIntent.Builder().setShowTitle(false).build();
            String browser=androidx.browser.customtabs.CustomTabsClient.getPackageName(this,null);
            if (browser!=null) launch.intent.setPackage(browser);
            launch.launchUrl(this,u);
        } catch (RuntimeException e) { toast("登录未完成，请重试"); }
    }
    private void openExternal(Uri uri) {
        try { startActivity(new Intent(Intent.ACTION_VIEW,uri)); } catch (ActivityNotFoundException e) { toast("未找到浏览器"); }
    }
    private ImageView preview(String path) {
        Bitmap thumb=thumbs.get(path);
        if (thumb==null) {
            BitmapFactory.Options opt=new BitmapFactory.Options(); opt.inSampleSize=4;
            thumb=BitmapFactory.decodeFile(new File(getFilesDir(),path).getPath(),opt);
            if (thumb!=null) thumbs.put(path,thumb);
        }
        ImageView img=new ImageView(this); img.setScaleType(ImageView.ScaleType.CENTER_CROP); img.setImageBitmap(thumb);
        img.setBackground(a.shape(PALE,12,0)); img.setClipToOutline(true); img.setContentDescription("照片"); return img;
    }
    private void drawAttachment() {
        attachment.removeAllViews(); attachment.setVisibility(photo==null?View.GONE:View.VISIBLE);
        if (photo!=null) {
            attachment.addView(preview(photo),a.size(68,62));
            attachment.addView(a.icon(R.drawable.ic_close,"移除照片",()->{discardPhoto(); drawAttachment(); updateSend();}),a.size(48,48));
        }
    }
    private void discardPhoto() { if (photo!=null) { new File(getFilesDir(),photo).delete(); photo=null; } }
    private void gallery() {
        photoChat=chatId;
        try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),PICK); }
        catch (ActivityNotFoundException e) { toast("相册不可用"); }
    }
    private void camera() {
        photoChat=chatId;
        try {
            File dir=new File(getCacheDir(),"camera"); dir.mkdirs(); File f=File.createTempFile("photo-",".jpg",dir); cameraPath=f.getPath();
            Uri dest=FileProvider.getUriForFile(this,getPackageName()+".photos",f);
            Intent take=new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT,dest);
            take.setClipData(ClipData.newRawUri("照片",dest));
            take.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(take,CAMERA);
        } catch (IOException|RuntimeException e) { clearCamera(); toast("相机不可用"); }
    }
    private void clearCamera() { if (cameraPath!=null) { new File(cameraPath).delete(); cameraPath=null; } }
    private void exportLog() {
        try { startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE,"DeepSeek-诊断日志.txt"),EXPORT); }
        catch (ActivityNotFoundException e) { toast("无法选择保存位置"); }
    }
    @Override protected void onActivityResult(int req,int result,Intent data) {
        super.onActivityResult(req,result,data);
        if (result!=RESULT_OK) { if (req==CAMERA) clearCamera(); return; }
        Uri uri=data==null?null:data.getData();
        if (req==EXPORT && uri!=null) {
            files.execute(()->{ try (OutputStream out=getContentResolver().openOutputStream(uri,"wt")) {
                    if (out==null) throw new IOException(); app.log.export(out); app.notice("日志已导出");
                } catch (Exception e) { app.notice("导出失败，请重试"); } }); return;
        }
        if (req!=PICK && req!=CAMERA) return;
        final File camera=req==CAMERA && cameraPath!=null?new File(cameraPath):null;
        final Uri src=camera!=null?Uri.fromFile(camera):uri;
        final long owner=photoChat; cameraPath=null;
        if (src==null) return;
        files.execute(()->{
            try {
                String path=Photos.importPhoto(this,src);
                runOnUiThread(()->{
                    if (isDestroyed() || chatId!=owner || !"chat".equals(screen)) { new File(getFilesDir(),path).delete(); return; }
                    discardPhoto(); photo=path; drawAttachment(); updateSend();
                });
            } catch (IOException|RuntimeException e) { app.notice("图片读取失败"); }
            finally { if (camera!=null) camera.delete(); }
        });
    }
    private void toast(String message) { app.notice(message); }
}
