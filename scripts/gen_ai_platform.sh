
current_dir="$(pwd)$(dirname "$0" | sed 's/^.//')"
parent_dir=$(dirname $current_dir)

echo "current_dir = ${current_dir}"
echo "parent_dir = ${parent_dir}"


cd "$parent_dir"
rm -rf "$current_dir/src"

"$current_dir/build.sh"

curl 'https://aiplatform.googleapis.com/$discovery/rest?version=v1' | "$parent_dir/.bin/codegen" --out-dir="$current_dir/src/main/scala" \
--specs=stdin \
--resources-pkg=gcp.ai.v1.resources.sttp4 \
--schemas-pkg=gcp.ai.v1.schemas \
--http-source=sttp4 \
--json-codec=ziojson \
--include-resources='projects.*'