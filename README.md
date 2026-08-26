# HookEuicc
解除某些软件对eSIM的要求，也可用于获取eSIM代码

在某些不直接提供eSIM激活码的应用，将会复制激活码到剪切板

## 其他功能

### OMAPI Bypass
绕过 ARA、ARF 限制。通常用于无ARA的卡进行OMAPI访问

#### mod版新增: 可选的调用方白名单, 以在无ARA安全机制的情况下保证安全调用

> [!NOTE]
> 需勾选 `com.android.se` 并重启手机 (或执行 `su -c killall com.android.se`)
> > mod版新增: 在完成Hook后开关是实时生效的
