/*
 * Android backend for UxPlay dnssd.c via NsdManager
 */

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <assert.h>

#include "dnssd.h"
#include "dnssdint.h"
#include "global.h"
#include "utils.h"

#define MAX_SERVNAME 256
#define MAX_TXT_ENTRIES 32
#define MAX_TXT_KEY 32
#define MAX_TXT_VAL 128

typedef struct {
    char key[MAX_TXT_KEY];
    char val[MAX_TXT_VAL];
} txt_entry_t;

typedef struct {
    txt_entry_t entries[MAX_TXT_ENTRIES];
    int count;
} txt_record_t;

typedef struct {
    txt_record_t raop_record;
    txt_record_t airplay_record;

    char codec_cn[16]; /* dynamic "cn" value, e.g. "0,1,2,3" */

    char raop_servname[MAX_SERVNAME];
} dnssd_private_t;

static dnssd_private_t *_priv(dnssd_t *dnssd) {
    assert(dnssd && dnssd->dnssd_private);
    return (dnssd_private_t *) dnssd->dnssd_private;
}

static void _txt_set(txt_record_t *rec, const char *key, const char *val) {
    for (int i = 0; i < rec->count; i++) {
        if (strcmp(rec->entries[i].key, key) == 0) {
            strncpy(rec->entries[i].val, val, MAX_TXT_VAL - 1);
            return;
        }
    }
    if (rec->count < MAX_TXT_ENTRIES) {
        strncpy(rec->entries[rec->count].key, key, MAX_TXT_KEY - 1);
        strncpy(rec->entries[rec->count].val, val, MAX_TXT_VAL - 1);
        rec->count++;
    }
}

void *dnssd_private_init(dnssd_t *dnssd, int *error) {
    if (error) *error = DNSSD_ERROR_NOERROR;
    dnssd_private_t *priv = (dnssd_private_t *) calloc(1, sizeof(dnssd_private_t));
    if (!priv) {
        if (error) *error = DNSSD_ERROR_OUTOFMEM;
        return NULL;
    }
    strncpy(priv->codec_cn, RAOP_CN, sizeof(priv->codec_cn) - 1);
    return priv;
}

void dnssd_private_destroy(void *priv) {
    free(priv);
}

void dnssd_error_text(int *error, const char *appname) {
    printf("dnssd (android NsdManager) failed with error code %d\n", *error);
}

int
dnssd_register_raop(dnssd_t *dnssd, unsigned short port)
{
    char features[22] = {0};
    dnssd_private_t *priv = _priv(dnssd);

    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd->features1, dnssd->features2);

    txt_record_t *rec = &priv->raop_record;
    rec->count = 0;

    _txt_set(rec, "ch", RAOP_CH);
    _txt_set(rec, "cn", priv->codec_cn);
    _txt_set(rec, "da", RAOP_DA);
    _txt_set(rec, "et", RAOP_ET);
    _txt_set(rec, "vv", RAOP_VV);
    _txt_set(rec, "ft", features);
    _txt_set(rec, "am", GLOBAL_MODEL);
    _txt_set(rec, "md", RAOP_MD);
    _txt_set(rec, "rhd", RAOP_RHD);

    switch (dnssd->pin_pw) {
    case 2:
    case 3:
        _txt_set(rec, "pw", "true");
        _txt_set(rec, "sf", "0x84");
        break;
    case 1:
        _txt_set(rec, "pw", "true");
        _txt_set(rec, "sf", "0x8c");
        break;
    default:
        _txt_set(rec, "pw", "false");
        _txt_set(rec, "sf", RAOP_SF);
        break;
    }

    _txt_set(rec, "sr", RAOP_SR);
    _txt_set(rec, "ss", RAOP_SS);
    _txt_set(rec, "sv", RAOP_SV);
    _txt_set(rec, "tp", RAOP_TP);
    _txt_set(rec, "txtvers", RAOP_TXTVERS);
    _txt_set(rec, "vs", RAOP_VS);
    _txt_set(rec, "vn", RAOP_VN);
    if (dnssd->pk) {
        _txt_set(rec, "pk", dnssd->pk);
    }

    /* Build service name: HW@Name */
    if (utils_hwaddr_raop(priv->raop_servname, sizeof(priv->raop_servname),
                          dnssd->hw_addr, dnssd->hw_addr_len) < 0) {
        return -1;
    }
    strncat(priv->raop_servname, "@", sizeof(priv->raop_servname) - strlen(priv->raop_servname) - 1);
    strncat(priv->raop_servname, dnssd->name, sizeof(priv->raop_servname) - strlen(priv->raop_servname) - 1);
    return 0;
}

int
dnssd_register_airplay(dnssd_t *dnssd, unsigned short port)
{
    char device_id[3 * MAX_HWADDR_LEN];
    char features[22] = {0};
    dnssd_private_t *priv = _priv(dnssd);

    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd->features1, dnssd->features2);

    if (utils_hwaddr_airplay(device_id, sizeof(device_id), dnssd->hw_addr, dnssd->hw_addr_len) < 0) {
        return -1;
    }

    txt_record_t *rec = &priv->airplay_record;
    rec->count = 0;

    _txt_set(rec, "deviceid", device_id);
    _txt_set(rec, "features", features);

    switch (dnssd->pin_pw) {
    case 1:
    case 2:
    case 3:
        _txt_set(rec, "pw", "true");
        break;
    default:
        _txt_set(rec, "pw", "false");
        break;
    }
    _txt_set(rec, "flags", "0x4");
    _txt_set(rec, "model", GLOBAL_MODEL);
    if (dnssd->pk) {
        _txt_set(rec, "pk", dnssd->pk);
    }
    _txt_set(rec, "pi", AIRPLAY_PI);
    _txt_set(rec, "srcvers", AIRPLAY_SRCVERS);
    _txt_set(rec, "vv", AIRPLAY_VV);
    return 0;
}

/* unregisters with NsdManager */
void dnssd_unregister_raop(dnssd_t *dnssd) {}
void dnssd_unregister_airplay(dnssd_t *dnssd) {}

const char *dnssd_get_raop_txt(dnssd_t *dnssd, int *length) {
    if (length) *length = 0;
    return NULL;
}

const char *dnssd_get_airplay_txt(dnssd_t *dnssd, int *length) {
    if (length) *length = 0;
    return NULL;
}

/* --- Android-specific accessors for JNI layer --- */

int android_dnssd_get_raop_txt_count(dnssd_t *dnssd) {
    return _priv(dnssd)->raop_record.count;
}

const char *android_dnssd_get_raop_txt_key(dnssd_t *dnssd, int index) {
    txt_record_t *rec = &_priv(dnssd)->raop_record;
    if (index < 0 || index >= rec->count) return NULL;
    return rec->entries[index].key;
}

const char *android_dnssd_get_raop_txt_val(dnssd_t *dnssd, int index) {
    txt_record_t *rec = &_priv(dnssd)->raop_record;
    if (index < 0 || index >= rec->count) return NULL;
    return rec->entries[index].val;
}

int android_dnssd_get_airplay_txt_count(dnssd_t *dnssd) {
    return _priv(dnssd)->airplay_record.count;
}

const char *android_dnssd_get_airplay_txt_key(dnssd_t *dnssd, int index) {
    txt_record_t *rec = &_priv(dnssd)->airplay_record;
    if (index < 0 || index >= rec->count) return NULL;
    return rec->entries[index].key;
}

const char *android_dnssd_get_airplay_txt_val(dnssd_t *dnssd, int index) {
    txt_record_t *rec = &_priv(dnssd)->airplay_record;
    if (index < 0 || index >= rec->count) return NULL;
    return rec->entries[index].val;
}

void android_dnssd_set_codecs(dnssd_t *dnssd, int alac, int aac) {
    /* Build cn string: 0=PCM (always), 1=ALAC, 2=AAC, 3=AAC-ELD */
    dnssd_private_t *priv = _priv(dnssd);
    char buf[16];
    int pos = 0;
    pos += snprintf(buf + pos, sizeof(buf) - pos, "0");
    if (alac) pos += snprintf(buf + pos, sizeof(buf) - pos, ",1");
    if (aac) pos += snprintf(buf + pos, sizeof(buf) - pos, ",2,3");
    strncpy(priv->codec_cn, buf, sizeof(priv->codec_cn) - 1);
}

const char *android_dnssd_get_raop_servname(dnssd_t *dnssd) {
    return _priv(dnssd)->raop_servname;
}
