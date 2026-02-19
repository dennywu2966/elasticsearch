#!/bin/bash
# Validation script for Lance Vector plugin on ES 9.2.4

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ES_PROJECT_DIR="/home/denny/projects/es-9.2.4-plugins"
PLUGIN_ZIP="$ES_PROJECT_DIR/plugins/lance-vector/build/distributions/lance-vector-9.2.4-SNAPSHOT.zip"
CLOUD_IAM_ZIP="$ES_PROJECT_DIR/plugins/security-realm-cloud-iam/build/distributions/security-realm-cloud-iam-9.2.4-SNAPSHOT.zip"

echo "=== Lance Vector Plugin Validation for ES 9.2.4 ==="
echo ""

# Check if plugin zip exists
if [ ! -f "$PLUGIN_ZIP" ]; then
    echo "❌ Plugin zip not found: $PLUGIN_ZIP"
    echo "   Run: ./gradlew :plugins:lance-vector:bundlePlugin"
    exit 1
fi

echo "✅ Plugin zip found: $PLUGIN_ZIP"
echo "   Size: $(du -h "$PLUGIN_ZIP" | cut -f1)"
echo ""

# Check if Cloud IAM plugin zip exists
if [ ! -f "$CLOUD_IAM_ZIP" ]; then
    echo "❌ Cloud IAM plugin zip not found: $CLOUD_IAM_ZIP"
    echo "   Run: ./gradlew :plugins:security-realm-cloud-iam:bundlePlugin"
    exit 1
fi

echo "✅ Cloud IAM plugin zip found: $CLOUD_IAM_ZIP"
echo "   Size: $(du -h "$CLOUD_IAM_ZIP" | cut -f1)"
echo ""

# Check ES distribution
ES_TAR="$ES_PROJECT_DIR/distribution/archives/linux-tar/build/distributions/elasticsearch-9.2.4-SNAPSHOT-linux-x86_64.tar.gz"
if [ ! -f "$ES_TAR" ]; then
    echo "⚠️  ES distribution not found: $ES_TAR"
    echo "   Building now... (this may take several minutes)"
    cd "$ES_PROJECT_DIR"
    ./gradlew :distribution:archives:linux-tar:assemble
fi

if [ -f "$ES_TAR" ]; then
    echo "✅ ES distribution found: $ES_TAR"
    echo "   Size: $(du -h "$ES_TAR" | cut -f1)"
    echo ""
fi

echo "=== Summary ==="
echo "Plugin files ready for installation."
echo ""
echo "Next steps:"
echo "1. Extract ES distribution:"
echo "   tar -xzf $ES_TAR"
echo ""
echo "2. Install plugins:"
echo "   cd elasticsearch-9.2.4-SNAPSHOT"
echo "   bin/elasticsearch-plugin install $PLUGIN_ZIP"
echo "   bin/elasticsearch-plugin install $CLOUD_IAM_ZIP"
echo ""
echo "3. Start ES:"
echo "   bin/elasticsearch"
echo ""
echo "4. Create test dataset:"
echo "   cd $ES_PROJECT_DIR"
echo "   python plugins/lance-vector/scripts/create_test_dataset.py /tmp/test-vectors.lance"
echo ""
echo "5. Run validation:"
echo "   See VALIDATION_GUIDE.md for detailed instructions"
