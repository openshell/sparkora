#!/usr/bin/env bash
# PostToolUse hook:检查刚被编辑的文件是否引入了 .env 中的真实密钥。
# 输入:stdin JSON;输出:告警到 stderr,不阻断(exit 0)。
input=$(cat)
file=$(printf '%s' "$input" | node -e '
  let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{
    try{const j=JSON.parse(s);
      const p=j.file_path||j.path||j.file||(j.tool_input&&(j.tool_input.file_path||j.tool_input.path))||"";
      process.stdout.write(String(p));
    }catch(e){process.stdout.write("");}
  })')

# 跳过 .env 自身与二进制
case "$file" in
  *.env|*.env.*|*target/*|*node_modules/*|*.jpg|*.png|*.gif|*.ico) exit 0 ;;
esac
[ -f "$file" ] || exit 0

# .env 固定取仓库根(本脚本位于 <workspace>/.reasonix/hooks/),不依赖调用方 cwd
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ENV_FILE="$ROOT/.env"
[ -f "$ENV_FILE" ] || exit 0

# 提取 .env 中"看起来是真实凭据"的值(排除 change-me/空/示例值),在改动文件中查找
# 单引号包裹的值先剥掉首尾引号,避免 'secret' 形式漏检
leak=$(grep -E '^[A-Z_]+=' "$ENV_FILE" 2>/dev/null \
  | grep -viE 'change-me|^(#|$)|_URL=|_HOST=|_PORT=|_TIMEOUT|_ENABLED|_MODEL|_BASE_URL' \
  | cut -d= -f2- | sed -e "s/^'//" -e "s/'$//" -e 's/^"//' -e 's/"$//' \
  | grep -E '.{16,}' \
  | while IFS= read -r secret; do grep -lF -- "$secret" "$file" 2>/dev/null; done | head -1)

if [ -n "$leak" ]; then
  echo "[secret-guard] 疑似密钥泄露: $file 中出现与 .env 一致的真实凭据,请立即移除并轮换密钥" >&2
fi
exit 0
