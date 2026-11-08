#!/usr/bin/env bash
# Publishes a release to a self-hosted GitLab: pushes the branch and the tag, then creates the
# release with docs/release-notes/<tag>.md as its description and the built installers attached
# as links, if they are there.
#
#   GITLAB_TOKEN=glpat-... scripts/publish-gitlab.sh v0.6.7
#
# The token is read from the environment and never written anywhere. Make one in GitLab under
# User settings > Access tokens with the api scope. Pass it on the command line as above, or
# export it for the shell; do not put it in a file in this repository.
#
#   GITLAB_URL      the GitLab, default https://git.evia.am
#   GITLAB_PROJECT  the project path, default evia/evia-paradigma
#   ASSET_DIR       a folder whose files are linked from the release (optional)
#
# The default is https, so the token is encrypted on the way. GITLAB_URL=http://dell works on the
# GitLab machine itself, where the request never leaves it.
set -uo pipefail
cd "$(dirname "$0")/.."

TAG="${1:-}"
URL="${GITLAB_URL:-https://git.evia.am}"
PROJECT="${GITLAB_PROJECT:-evia/evia-paradigma}"

die() { echo "publish-gitlab: $*" >&2; exit 1; }

[ -n "$TAG" ] || die "which tag? e.g. scripts/publish-gitlab.sh v0.6.7"
[ -n "${GITLAB_TOKEN:-}" ] || die "set GITLAB_TOKEN (GitLab > User settings > Access tokens, scope: api)"
NOTES="docs/release-notes/$TAG.md"
[ -f "$NOTES" ] || die "no $NOTES to use as the description"
git rev-parse -q --verify "refs/tags/$TAG" > /dev/null || die "the tag $TAG does not exist here; create it first"

ENC=$(printf '%s' "$PROJECT" | sed 's|/|%2F|g')
API="$URL/api/v4/projects/$ENC"
auth=(--header "PRIVATE-TOKEN: $GITLAB_TOKEN")

# ---- is the GitLab there, and is the project ours to write to?
name=$(curl -fsS "${auth[@]}" "$API" | sed -n 's/.*"path_with_namespace":"\([^"]*\)".*/\1/p') \
    || die "cannot reach $API - is the GitLab up, and is the token valid?"
echo "project : $name  on $URL"

# ---- the branch and the tag, over a URL carrying the token (never stored in .git/config)
remote="${URL%%://*}://oauth2:$GITLAB_TOKEN@${URL#*://}/$PROJECT.git"
echo "pushing : main and $TAG"
git push "$remote" "HEAD:refs/heads/main" "refs/tags/$TAG" 2>&1 | sed "s|$GITLAB_TOKEN|***|g" \
    || die "the push failed (see above)"

# ---- the release itself, described by the notes file
if curl -fsS "${auth[@]}" "$API/releases/$TAG" > /dev/null 2>&1; then
    echo "release : $TAG exists already, updating its description"
    method=PUT; endpoint="$API/releases/$TAG"
else
    echo "release : creating $TAG"
    method=POST; endpoint="$API/releases"
fi

# JSON by way of python, so the notes file's quotes and newlines cannot break the request
python3 - "$TAG" "$NOTES" <<'PY' > /tmp/evia-release.json
import json, sys
tag, notes = sys.argv[1], sys.argv[2]
body = {"tag_name": tag, "name": "EVIA Paradigma " + tag.lstrip("v"),
        "description": open(notes, encoding="utf-8").read()}
print(json.dumps(body))
PY
[ -s /tmp/evia-release.json ] || die "could not build the request body"

curl -fsS -X "$method" "${auth[@]}" --header "Content-Type: application/json" \
     --data @/tmp/evia-release.json "$endpoint" > /dev/null \
    || die "the release call failed"
rm -f /tmp/evia-release.json

# ---- the installers, as links on the release, when they have been built
if [ -n "${ASSET_DIR:-}" ] && [ -d "$ASSET_DIR" ]; then
    for f in "$ASSET_DIR"/*; do
        [ -f "$f" ] || continue
        up=$(curl -fsS "${auth[@]}" --form "file=@$f" "$API/uploads") || { echo "  ! $(basename "$f") did not upload"; continue; }
        path=$(printf '%s' "$up" | sed -n 's/.*"full_path":"\([^"]*\)".*/\1/p')
        [ -n "$path" ] || path=$(printf '%s' "$up" | sed -n 's/.*"url":"\([^"]*\)".*/\1/p')
        curl -fsS -X POST "${auth[@]}" \
             --data-urlencode "name=$(basename "$f")" \
             --data-urlencode "url=$URL$path" \
             "$API/releases/$TAG/assets/links" > /dev/null \
            && echo "  + $(basename "$f")" || echo "  ! $(basename "$f") did not link"
    done
fi

echo "done    $URL/$PROJECT/-/releases/$TAG"
