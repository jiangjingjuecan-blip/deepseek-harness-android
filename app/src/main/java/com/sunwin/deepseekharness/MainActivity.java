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
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.FileProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static com.sunwin.deepseekharness.Appearance.*;

public final class MainActivity extends Activity {
    private static final int PICK=20, CAMERA=21, EXPORT=22, DOCUMENT=23, NOTIFICATIONS=24;
    private HarnessApp app;
    private Appearance a;
    private RichMarkdown rich;
    private final ExecutorService files=Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnails=Executors.newSingleThreadExecutor();
    private final android.util.LruCache<String,Bitmap> thumbs=new android.util.LruCache<String,Bitmap>(4*1024*1024) {
        @Override protected int sizeOf(String key,Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private List<Attachment> draftFiles=Collections.emptyList();
    private List<Long> shownDraft;
    private HorizontalScrollView attachmentScroll;
    private long chatId, photoChat, photoBranch, draftBranch, tailUser, lastAssistant;
    private boolean pendingDice;
    private String screen="chat", draft="", cameraPath;
    private boolean openLogin, visible;
    private LinearLayout root, content, thread, intro, attachment;
    private ConversationScroll scroll;
    private History.Chat selectedChat;
    private ImageButton jumpButton;
    private ChatEngine.Work live,awaitingDraw;
    private Typewriter writer;
    private boolean stopIcon;
    private final Set<ChatEngine.Work> adoptions=new LinkedHashSet<>();
    private TextView header, subtitle, meter, branchButton;
    private EditText input;
    private ImageButton send, webToggle;
    private final HashMap<Long,Bubble> bubbles=new HashMap<>();
    private String introKey="";
    private android.content.SharedPreferences preferences() { return getSharedPreferences("ui",MODE_PRIVATE); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        app=(HarnessApp)getApplication(); a=new Appearance(this); rich=new RichMarkdown(this,a,this::openExternal);
        chatId=preferences().getLong("chat",0);
        if (app.history.chat(chatId)==null) {
            List<History.Chat> items=app.history.list();
            choose(items.isEmpty()?app.history.create():items.get(0).id);
        }
        draftBranch=app.history.chat(chatId).branch; draft=app.history.textDraft(draftBranch);
        if (state!=null) {
            screen=state.getString("screen","chat"); if (state.getLong("draftBranch")==draftBranch) draft=state.getString("draft","");
            cameraPath=state.getString("camera"); photoChat=state.getLong("photoChat"); photoBranch=state.getLong("photoBranch");
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
        super.onResume(); visible=true;
        if (live!=null && live.ended) { live=null; writer=null; }
        app.listen(this::update); app.listenStream(()->{ if (visible && "chat".equals(screen)) paintLive(); }); app.account.refresh(false); update();
    }
    private void saveDraft() {
        app.chat.savingDraft(draftBranch); app.history.textDraft(draftBranch,draft);
    }
    @Override public void onPause() {
        dispatchPending(); saveDraft(); visible=false; app.listen(null); app.listenStream(null);
        super.onPause();
    }
    @Override public void onDestroy() { files.shutdown(); thumbnails.shutdownNow(); super.onDestroy(); }
    @Override public void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        b.putLong("draftBranch",draftBranch); b.putLong("photoBranch",photoBranch); b.putString("screen",screen); b.putString("draft",draft);
        b.putString("camera",cameraPath); b.putLong("photoChat",photoChat); b.putBoolean("launch",openLogin);
    }
    @Override public void onBackPressed() {
        if (!"chat".equals(screen)) go("chat"); else super.onBackPressed();
    }
    private void choose(long id) { chatId=id; preferences().edit().putLong("chat",id).apply(); }
    private void selectChat(long id) {
        if (id!=chatId) choose(id);
        go("chat");
    }
    private void go(String page) {
        if (input!=null) getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(),0);
        screen=page; draw();
    }
    private void draw() { draw(null); }
    private void draw(ChatEngine.Work generating) {
        dispatchPending(); live=null; writer=null; adoptions.clear();
        long selected=app.history.chat(chatId).branch;
        if (draftBranch!=selected) {
            saveDraft(); draftBranch=selected; draft=app.history.textDraft(selected);
        }
        if (generating!=null && generating.chat.branch==selected) watch(generating);
        input=null; send=null; webToggle=null; stopIcon=false; introKey=""; bubbles.clear();
        root=a.column(); root.setBackgroundColor(PAPER); root.setFocusableInTouchMode(true);
        setContentView(root);
        boolean chat="chat".equals(screen);
        LinearLayout bar=a.row(); a.pad(bar,8,4); root.addView(bar,a.size(-1,62));
        bar.addView(a.icon(chat?R.drawable.ic_history:R.drawable.ic_back,chat?"历史对话":"返回",()->go(chat?"history":"chat")),a.size(48,48));
        LinearLayout titles=a.column(); titles.setGravity(Gravity.CENTER_VERTICAL); a.pad(titles,6,0);
        header=a.text("",18,INK,true); header.setSingleLine(true); header.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(header); subtitle=a.text("",12,DIM,false); titles.addView(subtitle);
        if (chat) titles.setOnClickListener(v->renameChat());
        bar.addView(titles,new LinearLayout.LayoutParams(0,-1,1));
        if (chat) {
            branchButton=a.button("分支",false,this::showBranches); a.buttonStyle(branchButton,PAPER,14);
            a.pad(branchButton,2,0); branchButton.setContentDescription("分支"); bar.addView(branchButton,a.size(52,44));
            meter=a.text("",12,DIM,false); meter.setGravity(Gravity.CENTER);
            a.buttonStyle(meter,PAPER,14); meter.setTooltipText("压缩上下文");
            meter.setOnClickListener(v->{
                PopupMenu menu=new PopupMenu(this,v);
                menu.getMenu().add("压缩上下文").setOnMenuItemClickListener(item->{ app.chat.compact(chatId); return true; });
                menu.show();
            });
            bar.addView(meter,a.size(48,48));
            bar.addView(a.icon(R.drawable.ic_settings,"设置",()->go("settings")),a.size(48,48));
        }
        if ("history".equals(screen)) bar.addView(a.icon(R.drawable.ic_plus,"新建对话",()->selectChat(app.history.create())),a.size(48,48));
        View rule=new View(this); rule.setBackgroundColor(LINE); root.addView(rule,new LinearLayout.LayoutParams(-1,1));
        content=a.column(); root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        if (chat) buildChat();
        else if ("history".equals(screen)) showHistory();
        update();
    }
    private void update() {
        if (root==null || isFinishing() || awaitingDraw!=null) return;
        if (openLogin && app.account.authorize!=null && visible && app.account.pending()) {
            openLogin=false; launchAuthorization(false);
        } else if (!app.account.loading && !app.account.pending() && !"".equals(app.account.phase)) openLogin=false;
        subtitle.setVisibility(View.GONE);
        switch (screen) {
            case "history": header.setText("历史对话"); break;
            case "settings": header.setText("设置"); showSettings(); break;
            default:
                History.Chat chat=app.history.chat(chatId);
                if (chat.branch!=draftBranch) { draw(); return; }
                selectedChat=chat; header.setText(chat.title); branchButton.setText(chat.number==1 ? "分支" : "分支 "+chat.number);
                String percent=app.history.meter(chatId,app.account.selected).percent();
                meter.setText(percent); meter.setContentDescription("上下文用量 "+percent);
                Loopback api=app.api;
                subtitle.setText(api!=null && app.account.stored ? app.account.modelStatus(api) : status());
                subtitle.setVisibility(View.VISIBLE);
                renderMessages(); drawAttachment(); styleWebToggle(); updateSend();
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
        FrameLayout viewport=new FrameLayout(this);
        content.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));
        scroll=new ConversationScroll(this,a.dp(48)); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        viewport.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        jumpButton=a.icon(R.drawable.ic_send,"跳到最新",scroll::latest);
        jumpButton.setTooltipText(null); jumpButton.setRotation(180); a.buttonStyle(jumpButton,WHITE,24); jumpButton.setElevation(a.dp(2));
        FrameLayout.LayoutParams jumpSize=new FrameLayout.LayoutParams(a.dp(44),a.dp(44),Gravity.BOTTOM|Gravity.END);
        jumpSize.setMargins(0,0,a.dp(16),a.dp(8)); viewport.addView(jumpButton,jumpSize); scroll.jumpButton(jumpButton);
        LinearLayout canvas=a.column(); a.pad(canvas,20,14); scroll.addView(canvas);
        intro=a.column(); intro.setGravity(Gravity.CENTER); canvas.addView(intro,new LinearLayout.LayoutParams(-1,0,1));
        thread=a.column(); canvas.addView(thread,a.size(-1,-2));
        LinearLayout composer=a.column(); a.pad(composer,8,6); composer.setBackground(a.shape(WHITE,22,LINE));
        LinearLayout.LayoutParams box=a.size(-1,-2); box.setMargins(a.dp(14),a.dp(4),a.dp(14),a.dp(12)); content.addView(composer,box);
        attachmentScroll=new ComposerInput.Tray(this); attachmentScroll.setHorizontalScrollBarEnabled(false);
        composer.addView(attachmentScroll,a.size(-1,-2));
        attachment=a.row(); attachmentScroll.addView(attachment); shownDraft=null;
        input=new ComposerInput(this); input.setTextSize(17); input.setTextColor(INK); input.setHintTextColor(DIM);
        input.setHint("输入消息"); input.setBackgroundColor(WHITE); a.pad(input,10,7);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NONE|android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        input.setOnEditorActionListener((v,action,event)->{
            if (event!=null || (action!=android.view.inputmethod.EditorInfo.IME_ACTION_SEND &&
                    action!=android.view.inputmethod.EditorInfo.IME_ACTION_DONE)) return false;
            Editable value=input.getText();
            if (android.view.inputmethod.BaseInputConnection.getComposingSpanStart(value)<0) {
                int start=Math.max(0,input.getSelectionStart()),end=Math.max(0,input.getSelectionEnd());
                value.replace(Math.min(start,end),Math.max(start,end),"\n");
            }
            return true;
        });
        input.setMaxLines(5); input.setMinHeight(a.dp(48)); input.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
        input.setText(draft); input.setSelection(input.length()); composer.addView(input,a.size(-1,-2));
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int st,int count,int after) { }
            public void onTextChanged(CharSequence s,int st,int before,int count) { draft=s.toString(); updateSend(); }
            public void afterTextChanged(Editable e) { }
        });
        LinearLayout tools=a.row(); composer.addView(tools);
        ImageButton gallery=a.icon(R.drawable.ic_gallery,"相册",this::gallery), camera=a.icon(R.drawable.ic_camera,"拍照",this::camera);
        for (ImageButton b:new ImageButton[]{gallery,camera}) { a.buttonStyle(b,WHITE,12); tools.addView(b,a.size(44,48)); }
        TextView file=a.button("文件",false,this::documents);
        a.buttonStyle(file,WHITE,12); a.pad(file,4,0); tools.addView(file,a.size(44,48));
        TextView dice=a.button("骰子",false,this::diceMenu); a.buttonStyle(dice,WHITE,12); a.pad(dice,2,0);
        tools.addView(dice,a.size(42,48));
        webToggle=a.icon(R.drawable.ic_web,"联网搜索",this::toggleWebSearch);
        tools.addView(webToggle,a.size(44,48));
        styleWebToggle();
        tools.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        send=a.icon(R.drawable.ic_send,"发送",this::send); a.buttonStyle(send,BLUE,15); send.setColorFilter(WHITE);
        android.animation.StateListAnimator press=new android.animation.StateListAnimator();
        press.addState(new int[]{android.R.attr.state_pressed},scaleSend(.94f));
        press.addState(new int[0],scaleSend(1f)); send.setStateListAnimator(press);
        tools.addView(send,a.size(48,46));
    }
    private android.animation.Animator scaleSend(float scale) {
        android.animation.ObjectAnimator animator=android.animation.ObjectAnimator.ofPropertyValuesHolder(send,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X,scale),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y,scale));
        animator.setDuration(85); return animator;
    }
    private void renderMessages() {
        List<History.Message> rows=app.history.messages(chatId);
        for (Iterator<ChatEngine.Work> it=adoptions.iterator();it.hasNext();) {
            ChatEngine.Work w=it.next();
            if (w.row==0) continue;
            adopt(w.localUser,w.user); adopt(w.localReply,w.row); it.remove();
        }
        List<ChatEngine.Work> outgoing=app.chat.outbox(draftBranch);
        ChatEngine.Work current=app.chat.latest;
        if (current!=null && current.chat.branch==draftBranch && !current.ended && current!=live) watch(current);
        History.Message tail=rows.isEmpty() ? null : rows.get(rows.size()-1);
        tailUser=tail!=null && "user".equals(tail.role) ? tail.id : 0;
        pendingDice=tail!=null && "dice".equals(tail.role);
        lastAssistant=0;
        for (History.Message row:rows) if ("assistant".equals(row.role)) lastAssistant=row.id;
        boolean empty=rows.isEmpty() && outgoing.isEmpty();
        intro.setVisibility(empty?View.VISIBLE:View.GONE);
        if (empty) {
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
        Bubble user=null;
        for (History.Message m:rows) {
            Bubble bubble=bubble(m);
            if ("user".equals(m.role)) { user=bubble; user.retry(null,0); user.progress(false,m.missing,m.documents); }
            if (user!=null && "assistant".equals(m.role) && "interrupted".equals(m.state) && !"已停止".equals(m.error))
                user.retry(null,m.id);
            if (live==null || m.id!=live.row) {
                bubble.webStatus("");
                bubble.sources(m.sources);
                body(bubble,m.text,m.state,m.error);
            } else if (bubble.text.length()==0 && (bubble.richBox==null || bubble.richBox.getChildCount()==0)) {
                bubble.webStatus(live.ended ? "" : live.webStatus);
                bubble.sources(live.sources);
                body(bubble,"","pending",null);
            }
        }
        for (ChatEngine.Work w:outgoing) {
            if (w.row==0) optimistic(w);
            else if (w.unsaved && bubbles.containsKey(w.row) && bubbles.containsKey(w.user)) {
                Bubble ans=bubbles.get(w.row); ans.webStatus(""); ans.sources(w.sources);
                body(ans,w.body,"interrupted",w.error); bubbles.get(w.user).retry(null,w.row);
            }
        }
        if (live!=null) scheduleFrame();
    }
    private void adopt(long key,long id) {
        if (key==id || id==0) return;
        Bubble bubble=bubbles.remove(key);
        if (bubble!=null) {
            bubble.id=id; Bubble duplicate=bubbles.put(id,bubble);
            if (duplicate!=null && duplicate!=bubble) thread.removeView(duplicate.group);
        }
    }
    private final class Bubble {
        long id,retryId,lastRichAt;
        History.Message metadata;
        final LinearLayout group=a.column(),actions=a.row();
        final TextView text;
        final View.OnClickListener tap;
        final View.OnLongClickListener hold;
        LinearLayout fileBox,webStatusChip,richBox,sourceRow;
        HorizontalScrollView sourceScroll;
        TextView retry,placeholder,webStatusText,errorNote;
        ProgressBar progress;
        ChatEngine.Work localRetry;
        Typewriter painted;
        int revision;
        String richRendered;
        List<Attachment> files=Collections.emptyList();
        List<WebTools.Source> shownSources=Collections.emptyList();
        Bubble(History.Message m) {
            id=m.id;
            boolean user="user".equals(m.role),dice="dice".equals(m.role);
            group.setGravity(user?Gravity.END:Gravity.START);
            LinearLayout.LayoutParams gp=a.size(-1,-2); gp.bottomMargin=a.dp(16);
            if (user) gp.leftMargin=a.dp(34); else gp.rightMargin=a.dp(18);
            thread.addView(group,gp); group.addView(actions,a.size(-2,-2));
            text=a.text("",16,INK,false); text.setTextIsSelectable(true);
            hold=v->{ if (id>0) messageMenu(id); return true; };
            tap=v->{ if (live!=null && isReply(this)) { writer.finish(); paintLive(); } };
            group.setOnLongClickListener(hold); text.setOnLongClickListener(hold);
            if (user || dice) { text.setBackground(a.shape(PALE,17,0)); a.pad(text,14,11); }
            else {
                a.pad(text,0,3); text.setOnClickListener(tap);
                group.setOnClickListener(tap);
                webStatusChip=a.row();
                webStatusChip.setBackground(a.shape(PALE,12,0));
                a.pad(webStatusChip,9,5);
                ImageView globe=new ImageView(MainActivity.this);
                globe.setImageResource(R.drawable.ic_web); globe.setColorFilter(BLUE);
                LinearLayout.LayoutParams glp=a.size(14,14); glp.rightMargin=a.dp(5);
                webStatusChip.addView(globe,glp);
                webStatusText=a.text("",12,BLUE,false);
                webStatusText.setSingleLine(true); webStatusText.setEllipsize(TextUtils.TruncateAt.END);
                webStatusChip.addView(webStatusText,a.size(-2,-2));
                webStatusChip.setVisibility(View.GONE);
                LinearLayout.LayoutParams wlp=a.size(-2,-2); wlp.topMargin=a.dp(2); wlp.bottomMargin=a.dp(4);
                group.addView(webStatusChip,wlp);
            }
            group.addView(text,a.size(-2,-2));
            if (!user && !dice) {
                richBox=a.column();
                richBox.setVisibility(View.GONE);
                richBox.setOnClickListener(tap); richBox.setOnLongClickListener(hold);
                group.addView(richBox,a.size(-1,-2));
                errorNote=a.text("",15,DIM,false);
                errorNote.setVisibility(View.GONE);
                LinearLayout.LayoutParams elp=a.size(-2,-2); elp.topMargin=a.dp(4);
                group.addView(errorNote,elp);
                sourceScroll=new HorizontalScrollView(MainActivity.this);
                sourceScroll.setHorizontalScrollBarEnabled(false);
                sourceScroll.setVisibility(View.GONE);
                sourceRow=a.row();
                sourceScroll.addView(sourceRow,new FrameLayout.LayoutParams(-2,-2));
                LinearLayout.LayoutParams slp=a.size(-1,-2); slp.topMargin=a.dp(8);
                group.addView(sourceScroll,slp);
            }
        }
        void webStatus(String status) {
            if (webStatusChip==null) return;
            boolean show=status!=null && !status.isEmpty();
            webStatusChip.setVisibility(show?View.VISIBLE:View.GONE);
            if (show && !TextUtils.equals(status,webStatusText.getText())) webStatusText.setText(status);
        }
        void sources(List<WebTools.Source> list) {
            if (sourceScroll==null) return;
            List<WebTools.Source> safe=list==null ? Collections.emptyList() : list;
            if (safe.equals(shownSources)) return;
            shownSources=new ArrayList<>(safe);
            sourceRow.removeAllViews();
            if (shownSources.isEmpty()) { sourceScroll.setVisibility(View.GONE); return; }
            for (int i=0;i<shownSources.size();i++) {
                WebTools.Source s=shownSources.get(i);
                LinearLayout chip=a.row();
                a.pad(chip,9,5);
                chip.setBackground(a.shape(WHITE,11,LINE));
                ImageView icon=new ImageView(MainActivity.this);
                icon.setImageResource(R.drawable.ic_web); icon.setColorFilter(BLUE);
                LinearLayout.LayoutParams ilp=a.size(12,12); ilp.rightMargin=a.dp(4);
                chip.addView(icon,ilp);
                String label=s.label();
                TextView tv=a.text(label,12,BLUE,false);
                tv.setSingleLine(true); tv.setMaxWidth(a.dp(170)); tv.setEllipsize(TextUtils.TruncateAt.END);
                chip.addView(tv,a.size(-2,-2));
                chip.setContentDescription("来源："+label);
                chip.setClickable(true);
                chip.setOnClickListener(v->openExternal(Uri.parse(s.url)));
                LinearLayout.LayoutParams clp=a.size(-2,-2);
                if (i>0) clp.leftMargin=a.dp(6);
                sourceRow.addView(chip,clp);
            }
            sourceScroll.setVisibility(View.VISIBLE);
        }
        void bind(History.Message m) {
            if (metadata==null || metadata.saved!=m.saved || metadata.version!=m.version || metadata.versions!=m.versions) {
                actions.removeAllViews(); progress=null; retry=null;
                if (!"user".equals(m.role)) actions.addView(a.text("dice".equals(m.role)?"骰子":"DeepSeek",12,DIM,true));
                if (m.saved) { TextView mark=a.text("存档",12,BLUE,true); a.pad(mark,5,0); actions.addView(mark); }
                if (m.versions>1) {
                    versionArrow(actions,m,-1); actions.addView(a.text(m.version+"/"+m.versions,12,DIM,false)); versionArrow(actions,m,1);
                }
                TextView more=a.button("⋯",false,()->{ if (id>0) messageMenu(id); });
                more.setContentDescription("消息操作"); a.buttonStyle(more,PAPER,10); a.pad(more,2,0); actions.addView(more,a.size(36,32));
            }
            metadata=m;
            if (!sameFiles(files,m.attachments)) {
                files=new ArrayList<>(m.attachments);
                if (fileBox==null) { fileBox=a.column(); group.addView(fileBox,1); }
                fileBox.removeAllViews(); messageFiles(fileBox,files);
            }
        }
        void progress(boolean show,int remaining,boolean document) {
            if (show && progress==null) { progress=new ProgressBar(MainActivity.this,null,android.R.attr.progressBarStyleSmall);
                progress.setContentDescription("处理中"); actions.addView(progress,a.size(18,18)); }
            if (progress!=null) progress.setVisibility(show?View.VISIBLE:View.GONE);
            if (remaining>0 && placeholder==null) {
                placeholder=a.text("",13,DIM,false); group.addView(placeholder,1);
            }
            if (placeholder!=null) {
                placeholder.setVisibility(remaining>0?View.VISIBLE:View.GONE);
                if (remaining>0) placeholder.setText((document?"文件":"照片")+" ×"+remaining);
            }
        }
        void retry(ChatEngine.Work work,long answer) {
            localRetry=work; retryId=answer;
            boolean show=work!=null || answer!=0;
            if (show && retry==null) {
                retry=a.button("重试",false,()->{
                    if (app.chat.busy() || !connectedForRetry()) return;
                    if (localRetry!=null) {
                        if (app.chat.retryLocal(localRetry)) { watch(localRetry); update(); } else toast("未连接");
                    } else if (app.chat.retry(chatId,id,retryId)) {
                        ChatEngine.Work next=app.chat.latest;
                        if (next.chat.branch!=draftBranch) draw(next); else { watch(next); update(); }
                    }
                    else toast("未连接");
                });
                actions.addView(retry,a.size(-2,34));
            }
            if (retry!=null) retry.setVisibility(show?View.VISIBLE:View.GONE);
        }
    }
    private static boolean sameFiles(List<Attachment> a,List<Attachment> b) {
        if (a.size()!=b.size()) return false;
        for (int i=0;i<a.size();i++) if (a.get(i).id!=b.get(i).id || !a.get(i).paths.equals(b.get(i).paths)) return false;
        return true;
    }
    private Bubble bubble(History.Message m) {
        Bubble b=bubbles.get(m.id);
        if (b==null) { b=new Bubble(m); bubbles.put(m.id,b); }
        b.bind(m); return b;
    }
    private void body(Bubble b,String value,String state,String error) {
        b.painted=null;
        String text=value==null ? "" : value;
        boolean assistant="assistant".equals(b.metadata.role);
        if ("dice".equals(b.metadata.role) && text.startsWith("[骰子 ") && text.endsWith("]"))
            text=text.substring(4,text.length()-1).replace(" | ","  ·  ");
        if ("user".equals(b.metadata.role) && text.isEmpty() && b.files.isEmpty() && b.placeholder==null) text="判定";
        if (assistant && !text.isEmpty() && MarkdownMath.hasRichSyntax(text)) {
            String note="interrupted".equals(state) ? (TextUtils.isEmpty(error)?"回复已中断":error)
                    : !TextUtils.isEmpty(error) ? error : "";
            String sig=text+"\u0000"+note;
            if (!sig.equals(b.richRendered)) {
                b.richRendered=sig;
                List<MarkdownMath.Block> blocks=MarkdownMath.parseBlocks(text);
                int color=b.text.getCurrentTextColor();
                float sizePx=b.text.getTextSize();
                if (blocks.size()==1 && blocks.get(0).type==MarkdownMath.BlockType.PROSE) {
                    if (b.richBox!=null) b.richBox.setVisibility(View.GONE);
                    rich.bindProse(b.text,blocks.get(0).content,sizePx,color);
                    b.text.setVisibility(View.VISIBLE);
                } else {
                    b.text.setVisibility(View.GONE);
                    if (b.richBox!=null) {
                        rich.bindBlocks(b.richBox,blocks,sizePx,color,b.tap,b.hold);
                        b.richBox.setVisibility(View.VISIBLE);
                    }
                }
                if (b.errorNote!=null) {
                    if (note.isEmpty()) b.errorNote.setVisibility(View.GONE);
                    else { b.errorNote.setText("（"+note+"）"); b.errorNote.setVisibility(View.VISIBLE); }
                }
            }
            return;
        }
        b.richRendered=null;
        if (b.richBox!=null) b.richBox.setVisibility(View.GONE);
        if (b.errorNote!=null) b.errorNote.setVisibility(View.GONE);
        if ("pending".equals(state) && text.isEmpty()) {
            boolean showingWeb=b.webStatusChip!=null && b.webStatusChip.getVisibility()==View.VISIBLE;
            text=showingWeb ? "" : "正在回复…";
        }
        if ("interrupted".equals(state)) {
            String reason=TextUtils.isEmpty(error)?"回复已中断":error;
            text=text.isEmpty()?reason:text+"\n（"+reason+"）";
        } else if (assistant && !TextUtils.isEmpty(error)) {
            text=text.isEmpty()?error:text+"\n（"+error+"）";
        }
        b.text.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE);
        if (!TextUtils.equals(text,b.text.getText())) b.text.setText(text);
    }
    private void optimistic(ChatEngine.Work w) {
        adoptions.add(w);
        intro.setVisibility(View.GONE);
        Bubble user;
        if (w.answerUser==0) {
            History.Message m=new History.Message(w.localUser,"user",w.text,w.attachments());
            user=bubble(m);
            user.progress(w.preparing,w.batch==null?0:w.batch.remaining.size(),w.batch!=null && w.batch.document);
            body(user,m.text,"done",null);
        } else user=bubbles.get(w.answerUser);
        if (user!=null) user.retry(w.error!=null?w:null,0);
        Bubble assistant=bubble(new History.Message(w.localReply,"assistant","",Collections.emptyList()));
        assistant.webStatus(w.ended ? "" : w.webStatus);
        assistant.sources(w.sources);
        body(assistant,"",w.error==null?"pending":"interrupted",w.error);
    }
    private void watch(ChatEngine.Work w) {
        if (live!=null && live!=w) { writer.finish(); paintLive(); }
        live=w; writer=new Typewriter(preferences().getInt(Typewriter.PREF,Typewriter.DEFAULT_SPEED));
    }
    private boolean isReply(Bubble b) { return b.id==live.row || b.id==live.localReply; }
    private void scheduleFrame() {
        if (visible && "chat".equals(screen) && live!=null) app.streamChanged();
    }
    private void paintLive() {
        if (live==null) return;
        Bubble b=bubbles.get(live.row==0?live.localReply:live.row);
        if (b==null) return;
        long now=SystemClock.uptimeMillis();
        writer.receive(live.body,now);
        if (live.ended) writer.end(now);
        if (live.error!=null || live.stopped) writer.finish();
        writer.advance(now);
        String received=writer.text();
        b.webStatus(live.ended || writer.length()>0 ? "" : live.webStatus);
        b.sources(live.sources);
        if (writer.length()==0 || live.error!=null) body(b,received.isEmpty() || writer.length()==0?"":received,
                live.error!=null?"interrupted":"pending",live.error);
        else {
            int n=writer.length();
            String shown=received.substring(0,n);
            boolean done=live.ended && !writer.moving();
            String toolErr=done && !live.webError.isEmpty() ? live.webError : null;
            if (MarkdownMath.hasRichSyntax(shown)) {
                if (done || n==received.length() || b.richRendered==null || now-b.lastRichAt>=80) {
                    b.lastRichAt=now;
                    body(b,shown,done?"done":"pending",toolErr);
                }
            } else {
                b.richRendered=null;
                if (b.richBox!=null) b.richBox.setVisibility(View.GONE);
                if (b.errorNote!=null) b.errorNote.setVisibility(View.GONE);
                if (toolErr!=null) {
                    body(b,shown,"done",toolErr);
                } else {
                    int previous=b.text.length();
                    if (b.painted!=writer || b.revision!=writer.revision() || previous>n) {
                        b.text.setText(shown,TextView.BufferType.EDITABLE);
                        b.painted=writer; b.revision=writer.revision();
                    } else if (previous<n) b.text.append(received,previous,n);
                    b.text.setVisibility(View.VISIBLE);
                }
            }
        }
        Bubble user=bubbles.get(live.user!=0?live.user:live.localUser);
        if (user!=null) user.progress(live.preparing && !live.ended,live.batch!=null?live.batch.remaining.size():0,
                live.batch!=null && live.batch.document);
        if (!live.ended && live.usage!=null) {
            String percent=live.usage.percent();
            if (!TextUtils.equals(percent,meter.getText())) { meter.setText(percent); meter.setContentDescription("上下文用量 "+percent); }
        }
        if (writer.moving()) scheduleFrame();
    }
    private void updateSend() {
        if (send==null) return;
        ChatEngine.Work stopping=app.chat.stoppingTarget();
        boolean stop=stopping!=null && stopping.chat.id==chatId;
        if (stopIcon!=stop) {
            stopIcon=stop; String label=stop?"停止":"发送";
            send.setImageResource(stop?R.drawable.ic_stop:R.drawable.ic_send); send.setContentDescription(label); send.setTooltipText(label);
        }
        boolean can=stop ? !stopping.stopped : !app.chat.busy() && (!draft.trim().isEmpty() || !draftFiles.isEmpty() ||
                app.imports.available(chatId,draftBranch)!=null || pendingDice || tailUser!=0);
        send.setEnabled(can); send.setAlpha(can?1f:.40f);
    }
    private boolean connectedForRetry() {
        if (app.api==null) { if (retryService()) startRuntime(); else toast(status()); return false; }
        if (!app.account.stored) { login(); return false; }
        if (app.account.selected.isEmpty()) { app.account.refresh(true); toast("模型未就绪"); return false; }
        return true;
    }
    private void send() {
        if (stopIcon) { app.chat.interrupt(); updateSend(); return; }
        DraftImporter.Batch batch=app.imports.available(chatId,draftBranch);
        long answer=draft.trim().isEmpty() && draftFiles.isEmpty() && batch==null ? tailUser : 0;
        ChatEngine.Work w=app.chat.reserve(selectedChat,draft.trim(),draftFiles,batch,answer,pendingDice);
        if (w==null) return;
        awaitingDraw=w;
        send.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        watch(w); input.setText(""); showDraft(Collections.emptyList()); optimistic(w); updateSend();
        View surface=root;
        surface.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            public boolean onPreDraw() {
                surface.getViewTreeObserver().removeOnPreDrawListener(this);
                Bubble user=bubbles.get(w.localUser); if (awaitingDraw==w && user!=null) scroll.showNew(user.group);
                return true;
            }
        });
        surface.getViewTreeObserver().addOnDrawListener(new ViewTreeObserver.OnDrawListener() {
            boolean posted;
            public void onDraw() {
                if (posted) return; posted=true;
                surface.post(()->{
                    if (surface.getViewTreeObserver().isAlive()) surface.getViewTreeObserver().removeOnDrawListener(this);
                    if (awaitingDraw==w) dispatchPending();
                });
            }
        });
        surface.invalidate();
    }
    private void dispatchPending() {
        if (awaitingDraw!=null) { ChatEngine.Work w=awaitingDraw; awaitingDraw=null; app.chat.start(w); }
    }
    private void versionArrow(LinearLayout row,History.Message m,int direction) {
        TextView arrow=a.button(direction<0?"‹":"›",false,()->{
            long id=app.history.switchVersion(chatId,m.id,direction); draw(); jump(id);
        });
        boolean enabled=direction<0 ? m.version>1 : m.version<m.versions;
        arrow.setContentDescription(direction<0?"上一版":"下一版"); arrow.setEnabled(enabled); arrow.setAlpha(enabled?1f:.3f);
        a.buttonStyle(arrow,PAPER,10); a.pad(arrow,0,0); row.addView(arrow,a.size(36,32));
    }
    private void jump(long id) {
        Bubble bubble=bubbles.get(id);
        if (bubble==null) return;
        scroll.post(()->{
            Rect rect=new Rect(); View group=bubble.group;
            group.getDrawingRect(rect); scroll.offsetDescendantRectToMyCoords(group,rect); scroll.anchor(rect.top);
        });
    }
    private void showBranches() {
        List<String> labels=new ArrayList<>(); List<Runnable> actions=new ArrayList<>();
        for (History.Branch b:app.history.branches(chatId)) {
            labels.add((b.id==draftBranch?"✓  ":"")+b.label());
            actions.add(()->{ app.history.selectBranch(chatId,b.id); draw(); });
        }
        for (History.Message m:app.history.messages(chatId)) if (m.saved) {
            String name=m.text.isEmpty() && !m.attachments.isEmpty() ? m.attachments.get(0).name : m.text;
            name=name.replace('\n',' ').trim();
            labels.add("存档 · "+name.substring(0,name.offsetByCodePoints(0,Math.min(16,name.codePointCount(0,name.length())))));
            actions.add(()->jump(m.id));
        }
        new AlertDialog.Builder(this).setTitle("分支").setItems(labels.toArray(new String[0]),(d,i)->actions.get(i).run()).show();
    }
    private void styleWebToggle() {
        if (webToggle==null) return;
        boolean on=app.webSearchEnabled();
        webToggle.setSelected(on); webToggle.setActivated(on);
        webToggle.setColorFilter(on ? BLUE : DIM);
        a.buttonStyle(webToggle,on ? PALE : WHITE,12);
    }
    private void toggleWebSearch() {
        app.webSearchEnabled(!app.webSearchEnabled());
        if (webToggle!=null) webToggle.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        styleWebToggle();
    }
    private void renameChat() {
        if (selectedChat==null) return;
        EditText field=new EditText(this); field.setSingleLine();
        field.setText(selectedChat.title); field.setSelection(field.length());
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("重命名").setView(field)
                .setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String value=field.getText().toString().trim();
            if (value.isEmpty()) return;
            app.history.rename(chatId,value); dialog.dismiss(); update();
        }));
        dialog.show();
    }
    private void messageMenu(long id) {
        History.Message m=app.history.at(draftBranch,id);
        List<String> labels=new ArrayList<>(); List<Runnable> actions=new ArrayList<>();
        if (m.text!=null && !m.text.isEmpty()) {
            labels.add("复制");
            actions.add(()->{
                android.content.ClipboardManager cm=getSystemService(android.content.ClipboardManager.class);
                if (cm!=null) { cm.setPrimaryClip(ClipData.newPlainText("message",m.text)); toast("已复制"); }
            });
        }
        labels.add(m.saved?"取消存档":"存档");
        actions.add(()->{ app.history.bookmark(draftBranch,id,!m.saved); draw(); jump(id); });
        if (!app.chat.busy()) {
            labels.add("分支"); actions.add(()->{ app.history.fork(chatId,id); draw(); });
            if (!"dice".equals(m.role)) {
                labels.add("编辑"); actions.add(()->editMessage(m));
                if ("assistant".equals(m.role) && id==lastAssistant) {
                    labels.add("重生成");
                    actions.add(()->{ if (app.chat.regenerate(chatId,id)) draw(app.chat.latest); else toast("未连接"); });
                }
            }
        }
        new AlertDialog.Builder(this).setItems(labels.toArray(new String[0]),(d,i)->actions.get(i).run()).show();
    }
    private void editMessage(History.Message m) {
        EditText field=new EditText(this); field.setMinLines(2); field.setMaxLines(8);
        field.setText(m.text); field.setSelection(field.length());
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("编辑").setView(field)
                .setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String value=field.getText().toString();
            if (value.trim().isEmpty() && m.attachments.isEmpty()) { toast("内容为空"); return; }
            if (!value.equals(m.text)) app.history.edit(chatId,m.id,value);
            dialog.dismiss(); draw();
        }));
        dialog.show();
    }
    private void diceMenu() {
        if (app.chat.busy()) { toast("正在处理"); return; }
        LinearLayout panel=a.column(); a.pad(panel,18,8);
        EditText field=new EditText(this); field.setSingleLine(); field.setHint("2d6+1"); panel.addView(field);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("骰子").setView(panel)
                .setNegativeButton("取消",null).setPositiveButton("掷骰",null).create();
        LinearLayout row=null;
        for (int i=0;i<Dice.FACES.length;i++) {
            if (i%4==0) { row=a.row(); panel.addView(row); }
            String expr="d"+Dice.FACES[i];
            TextView button=a.button(expr,false,()->roll(expr,dialog)); a.pad(button,2,0);
            row.addView(button,new LinearLayout.LayoutParams(0,a.dp(42),1));
        }
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->roll(field.getText().toString(),dialog)));
        dialog.show();
    }
    private void roll(String expression,AlertDialog dialog) {
        try {
            app.history.roll(chatId,Dice.roll(expression)); dialog.dismiss(); update();
        } catch (IllegalArgumentException e) { toast("骰式无效"); }
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
    private View row(LinearLayout card,String left,String right,Runnable action) {
        LinearLayout line=a.row(); a.pad(line,14,11);
        TextView text=a.text(left,16,INK,false); line.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        if (!right.isEmpty()) line.addView(a.text(right,14,DIM,false));
        if (action!=null) {
            ImageView next=new ImageView(this); next.setImageResource(R.drawable.ic_chevron); next.setColorFilter(DIM);
            line.addView(next,a.size(21,21)); a.buttonStyle(line,WHITE,13); line.setOnClickListener(v->action.run());
        }
        line.setMinimumHeight(a.dp(52)); card.addView(line,a.size(-1,-2)); return line;
    }
    private void showHistory() {
        LinearLayout page=page(), list=card(page);
        for (History.Chat chat:app.history.list()) {
            View line=row(list,chat.title,chat.id==chatId?"当前":"",()->selectChat(chat.id));
            line.setOnLongClickListener(v->{
                // A handled long click gives one system haptic before the posted dialog opens.
                v.post(()->confirmDelete(chat.id));
                return true;
            });
        }
    }
    private void confirmDelete(long id) {
        if (isFinishing() || isDestroyed() || !"history".equals(screen)) return;
        new AlertDialog.Builder(this).setTitle("删除对话").setNegativeButton("取消",null)
            .setPositiveButton("删除",(dialog,which)->{
                try {
                    long next=app.chat.delete(id,chatId);
                    if (next!=chatId) selectChat(next); else showHistory();
                } catch (android.database.sqlite.SQLiteException e) {
                    app.log.add(Diagnostics.Event.CHAT_DELETE_FAILED); toast("删除失败");
                }
            }).show();
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
        a.gap(page,28); LinearLayout other=card(page);
        int speed=preferences().getInt(Typewriter.PREF,Typewriter.DEFAULT_SPEED);
        row(other,"显示速度",speed==0?"即时":speed+" 字/秒",()->{
            int[] speeds={4,8,16,0}; String[] names={"4 字/秒","8 字/秒","16 字/秒","即时"};
            new AlertDialog.Builder(this).setTitle("显示速度").setItems(names,(d,i)->{
                preferences().edit().putInt(Typewriter.PREF,speeds[i]).apply(); showSettings();
            }).show();
        });
        row(other,"导出诊断日志","",this::exportLog);
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
    private ImageView preview(String path,String label) {
        ImageView img=new ImageView(this) {
            private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
            private final RectF bounds=new RectF();
            private boolean loading,failed;
            @Override protected void onDraw(Canvas canvas) {
                // The history uses a ScrollView. Off-screen bubbles must not pin every decoded bitmap.
                Bitmap bitmap=thumbs.get(path);
                if (bitmap!=null) {
                    float scale=Math.max((float)getWidth()/bitmap.getWidth(),(float)getHeight()/bitmap.getHeight());
                    float w=bitmap.getWidth()*scale, h=bitmap.getHeight()*scale;
                    bounds.set((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);
                    canvas.drawBitmap(bitmap,null,bounds,paint);
                } else if (failed) super.onDraw(canvas);
                else if (!loading && !thumbnails.isShutdown()) {
                    loading=true;
                    int edge=Math.min(440,a.dp(148));
                    post(()->{
                        if (thumbnails.isShutdown()) return;
                        thumbnails.execute(()->{
                            try {
                                Bitmap decoded=Photos.load(MainActivity.this,path,edge);
                                thumbs.put(path,decoded); post(()->{ loading=false; invalidate(); });
                            } catch (IOException e) {
                                post(()->{ loading=false; failed=true; setImageResource(R.drawable.ic_gallery); });
                            }
                        });
                    });
                }
            }
        };
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(a.shape(PALE,12,0)); img.setClipToOutline(true); img.setContentDescription(label);
        return img;
    }
    private List<String> imagePaths(List<Attachment> items) {
        List<String> paths=new ArrayList<>();
        for (Attachment item:items) paths.addAll(item.imagePaths());
        return paths;
    }
    private void messageFiles(LinearLayout group,List<Attachment> items) {
        List<String> paths=imagePaths(items);
        int width=getResources().getDisplayMetrics().widthPixels;
        int side=Math.min(96,Math.max(48,(int)((width-a.dp(88))/getResources().getDisplayMetrics().density/3)));
        LinearLayout row=null;
        for (int i=0;i<paths.size();i++) {
            if (i%3==0) { row=a.row(); group.addView(row,a.size(-2,-2)); }
            final int index=i;
            ImageView image=preview(paths.get(i),"照片 "+(i+1));
            image.setOnClickListener(v->PhotoViewer.open(this,paths,index));
            LinearLayout.LayoutParams size=paths.size()==1 ? a.size(148,116) : a.size(side,side);
            size.setMargins(a.dp(2),a.dp(2),a.dp(2),a.dp(2)); row.addView(image,size);
        }
        for (Attachment item:items) if (item.kind!=Attachment.Kind.PHOTO) group.addView(fileChip(item),a.size(-2,42));
        a.gap(group,4);
    }
    private TextView fileChip(Attachment item) {
        TextView chip=a.button(item.name,false,()->openDocument(item));
        chip.setSingleLine(); chip.setMaxWidth(a.dp(245)); chip.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        return chip;
    }
    private void openDocument(Attachment item) {
        if (item.kind==Attachment.Kind.SCAN) { PhotoViewer.open(this,item.paths,0); return; }
        files.execute(()->{
            try {
                String value=new String(Files.readAllBytes(Attachment.file(this,item.paths.get(0)).toPath()),StandardCharsets.UTF_8);
                runOnUiThread(()->{
                    if (isFinishing() || isDestroyed()) return;
                    TextView text=a.text(value,15,INK,false); a.pad(text,20,12); text.setTextIsSelectable(true);
                    if (!item.imageOffsets.isEmpty()) {
                        SpannableString body=new SpannableString(value);
                        for (int i=0;i<item.imageOffsets.size();i++) {
                            int at=item.imageOffsets.get(i), index=i;
                            String mark=Attachment.marker(i+1);
                            if (value.startsWith(mark,at)) body.setSpan(new ClickableSpan() {
                                @Override public void onClick(View v) { PhotoViewer.open(MainActivity.this,item.imagePaths(),index); }
                            },at,at+mark.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        text.setText(body); text.setMovementMethod(LinkMovementMethod.getInstance());
                    }
                    ScrollView view=new ScrollView(this); view.addView(text);
                    new AlertDialog.Builder(this).setTitle(item.name).setView(view).setPositiveButton("关闭",null).show();
                });
            } catch (IOException e) { app.notice("无法读取该文件"); }
        });
    }
    private void drawAttachment() {
        DraftImporter.Batch batch=app.imports.available(chatId,draftBranch);
        boolean complete=batch!=null && batch.done.getCount()==0;
        List<Attachment> files=app.history.branchDraft(draftBranch);
        for (ChatEngine.Work w:app.chat.outbox(draftBranch)) {
            Set<Long> held=new HashSet<>(); for (Attachment f:w.attachments()) held.add(f.id);
            files.removeIf(f->held.contains(f.id));
        }
        if (complete) app.imports.observed(batch);
        showDraft(files);
    }
    private void showDraft(List<Attachment> files) {
        draftFiles=files;
        List<Long> ids=new ArrayList<>();
        for (Attachment item:draftFiles) ids.add(item.id);
        if (ids.equals(shownDraft)) return;
        shownDraft=ids;
        attachment.removeAllViews();
        List<String> paths=imagePaths(draftFiles);
        for (Attachment item:draftFiles) {
            if (item.kind==Attachment.Kind.PHOTO) {
                String path=item.paths.get(0);
                ImageView img=preview(path,"待发照片 "+(paths.indexOf(path)+1));
                img.setOnClickListener(v->PhotoViewer.open(this,paths,paths.indexOf(path)));
                attachment.addView(img,a.size(62,62));
            } else attachment.addView(fileChip(item),a.size(-2,42));
            attachment.addView(a.icon(R.drawable.ic_close,item.kind==Attachment.Kind.PHOTO ? "移除照片" : "移除文件",()->{
                app.history.removeDraft(chatId,item.id,app.log); drawAttachment(); updateSend();
            }),a.size(44,44));
        }
    }
    private void gallery() {
        if (app.imports.busy(chatId)) return;
        int slots=Attachment.MAX_PHOTOS-Attachment.photos(draftFiles);
        if (slots<=0) { toast("最多 9 张图片"); return; }
        photoChat=chatId; photoBranch=draftBranch;
        if (Build.VERSION.SDK_INT>=33) {
            Intent pick=new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
            if (slots>1) pick.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,Math.min(slots,MediaStore.getPickImagesMaxLimit()));
            try { startActivityForResult(pick,PICK); return; } catch (ActivityNotFoundException ignored) {}
        }
        try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,slots>1),PICK); }
        catch (ActivityNotFoundException e) { toast("相册不可用"); }
    }
    private void documents() {
        if (app.imports.busy(chatId)) return;
        if (Attachment.documents(draftFiles)>=Attachment.MAX_DOCUMENTS) { toast("最多 4 个文件"); return; }
        photoChat=chatId; photoBranch=draftBranch;
        try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true),DOCUMENT); }
        catch (ActivityNotFoundException e) { toast("无法选择文件"); }
    }
    private void camera() {
        if (app.imports.busy(chatId)) return;
        if (Attachment.photos(draftFiles)>=Attachment.MAX_PHOTOS) { toast("最多 9 张图片"); return; }
        photoChat=chatId; photoBranch=draftBranch;
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
        if (req!=PICK && req!=CAMERA && req!=DOCUMENT) return;
        File camera=req==CAMERA && cameraPath!=null ? new File(cameraPath) : null;
        cameraPath=null;
        if (photoChat!=chatId || photoBranch!=draftBranch) { if (camera!=null) camera.delete(); return; }
        Set<Uri> sources=new LinkedHashSet<>();
        if (camera!=null) sources.add(Uri.fromFile(camera));
        else if (data!=null && data.getClipData()!=null) {
            ClipData clip=data.getClipData();
            int cap=req==DOCUMENT ? Attachment.MAX_DOCUMENTS : Attachment.MAX_PHOTOS;
            for (int i=0;i<Math.min(clip.getItemCount(),cap+1);i++) {
                Uri picked=clip.getItemAt(i).getUri();
                if (picked!=null) sources.add(picked);
            }
        } else if (uri!=null) sources.add(uri);
        if (!sources.isEmpty()) app.imports.add(photoChat,new ArrayList<>(sources),req==DOCUMENT,camera);
    }
    private void toast(String message) { app.notice(message); }
}
