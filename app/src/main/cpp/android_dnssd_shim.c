/*
 * Android dnssd shim -- implements dnssd.h API without dns_sd.h.
 * Stores TXT records as key=value maps in memory.
 * Actual mDNS registration is done in Kotlin via NsdManager.
 */

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <assert.h>

#include "dnssd.h"
#include "dnssdint.h"
#include "global.h"
#include "utils.h"

#define MAX_DEVICEID 18
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
    int registered;
} txt_record_t;

struct dnssd_s {
    txt_record_t raop_record;
    txt_record_t airplay_record;

    char *name;
    int name_len;

    char *hw_addr;
    int hw_addr_len;

    char *pk;

    uint32_t features1;
    uint32_t features2;

    unsigned char pin_pw;

    char raop_servname[MAX_SERVNAME];
    unsigned short raop_port;
    unsigned short airplay_port;
};

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

dnssd_t *
dnssd_init(const char *name, int name_len, const char *hw_addr, int hw_addr_len, int *error, unsigned char pin_pw)
{
    if (error) *error = DNSSD_ERROR_NOERROR;

    dnssd_t *dnssd = (dnssd_t *)calloc(1, sizeof(dnssd_t));
    if (!dnssd) {
        if (error) *error = DNSSD_ERROR_OUTOFMEM;
        return NULL;
    }

    dnssd->pin_pw = pin_pw;

    char *end = NULL;
    unsigned long features = strtoul(FEATURES_1, &end, 16);
    if (!end || (features & 0xFFFFFFFF) != features) {
        free(dnssd);
        if (error) *error = DNSSD_ERROR_BADFEATURES;
        return NULL;
    }
    dnssd->features1 = (uint32_t)features;

    features = strtoul(FEATURES_2, &end, 16);
    if (!end || (features & 0xFFFFFFFF) != features) {
        free(dnssd);
        if (error) *error = DNSSD_ERROR_BADFEATURES;
        return NULL;
    }
    dnssd->features2 = (uint32_t)features;

    dnssd->name_len = name_len;
    dnssd->name = calloc(1, name_len + 1);
    if (!dnssd->name) {
        free(dnssd);
        if (error) *error = DNSSD_ERROR_OUTOFMEM;
        return NULL;
    }
    memcpy(dnssd->name, name, name_len);

    dnssd->hw_addr_len = hw_addr_len;
    dnssd->hw_addr = calloc(1, hw_addr_len);
    if (!dnssd->hw_addr) {
        free(dnssd->name);
        free(dnssd);
        if (error) *error = DNSSD_ERROR_OUTOFMEM;
        return NULL;
    }
    memcpy(dnssd->hw_addr, hw_addr, hw_addr_len);

    return dnssd;
}

void
dnssd_destroy(dnssd_t *dnssd)
{
    if (dnssd) {
        free(dnssd->name);
        free(dnssd->hw_addr);
        free(dnssd);
    }
}

int
dnssd_register_raop(dnssd_t *dnssd, unsigned short port)
{
    char features[22] = {0};
    assert(dnssd);

    dnssd->raop_port = port;
    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd->features1, dnssd->features2);

    txt_record_t *rec = &dnssd->raop_record;
    rec->count = 0;

    _txt_set(rec, "ch", RAOP_CH);
    _txt_set(rec, "cn", RAOP_CN);
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
    if (utils_hwaddr_raop(dnssd->raop_servname, sizeof(dnssd->raop_servname),
                          dnssd->hw_addr, dnssd->hw_addr_len) < 0) {
        return -1;
    }
    strncat(dnssd->raop_servname, "@", sizeof(dnssd->raop_servname) - strlen(dnssd->raop_servname) - 1);
    strncat(dnssd->raop_servname, dnssd->name, sizeof(dnssd->raop_servname) - strlen(dnssd->raop_servname) - 1);

    rec->registered = 1;
    return 0; /* success -- actual mDNS registration done in Kotlin */
}

int
dnssd_register_airplay(dnssd_t *dnssd, unsigned short port)
{
    char device_id[3 * MAX_HWADDR_LEN];
    char features[22] = {0};
    assert(dnssd);

    dnssd->airplay_port = port;
    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd->features1, dnssd->features2);

    if (utils_hwaddr_airplay(device_id, sizeof(device_id), dnssd->hw_addr, dnssd->hw_addr_len) < 0) {
        return -1;
    }

    txt_record_t *rec = &dnssd->airplay_record;
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

    rec->registered = 1;
    return 0;
}

void dnssd_unregister_raop(dnssd_t *dnssd) {
    assert(dnssd);
    dnssd->raop_record.registered = 0;
}

void dnssd_unregister_airplay(dnssd_t *dnssd) {
    assert(dnssd);
    dnssd->airplay_record.registered = 0;
}

const char *dnssd_get_raop_txt(dnssd_t *dnssd, int *length) {
    /* Not used on Android -- TXT records retrieved via android_dnssd_* helpers */
    if (length) *length = 0;
    return NULL;
}

const char *dnssd_get_airplay_txt(dnssd_t *dnssd, int *length) {
    if (length) *length = 0;
    return NULL;
}

const char *dnssd_get_name(dnssd_t *dnssd, int *length) {
    *length = dnssd->name_len;
    return dnssd->name;
}

const char *dnssd_get_hw_addr(dnssd_t *dnssd, int *length) {
    *length = dnssd->hw_addr_len;
    return dnssd->hw_addr;
}

uint64_t dnssd_get_airplay_features(dnssd_t *dnssd) {
    uint64_t features = ((uint64_t)dnssd->features2) << 32;
    features += (uint64_t)dnssd->features1;
    return features;
}

void dnssd_set_pk(dnssd_t *dnssd, char *pk_str) {
    dnssd->pk = pk_str;
}

void dnssd_set_airplay_features(dnssd_t *dnssd, int bit, int val) {
    uint32_t mask = 0;
    uint32_t *features = 0;
    if (bit < 0 || bit > 63) return;
    if (val < 0 || val > 1) return;
    if (bit >= 32) {
        mask = 0x1 << (bit - 32);
        features = &(dnssd->features2);
    } else {
        mask = 0x1 << bit;
        features = &(dnssd->features1);
    }
    if (val) {
        *features = *features | mask;
    } else {
        *features = *features & ~mask;
    }
}

/* --- Android-specific accessors for JNI layer --- */

int android_dnssd_get_raop_txt_count(dnssd_t *dnssd) {
    return dnssd->raop_record.count;
}

const char *android_dnssd_get_raop_txt_key(dnssd_t *dnssd, int index) {
    if (index < 0 || index >= dnssd->raop_record.count) return NULL;
    return dnssd->raop_record.entries[index].key;
}

const char *android_dnssd_get_raop_txt_val(dnssd_t *dnssd, int index) {
    if (index < 0 || index >= dnssd->raop_record.count) return NULL;
    return dnssd->raop_record.entries[index].val;
}

int android_dnssd_get_airplay_txt_count(dnssd_t *dnssd) {
    return dnssd->airplay_record.count;
}

const char *android_dnssd_get_airplay_txt_key(dnssd_t *dnssd, int index) {
    if (index < 0 || index >= dnssd->airplay_record.count) return NULL;
    return dnssd->airplay_record.entries[index].key;
}

const char *android_dnssd_get_airplay_txt_val(dnssd_t *dnssd, int index) {
    if (index < 0 || index >= dnssd->airplay_record.count) return NULL;
    return dnssd->airplay_record.entries[index].val;
}

const char *android_dnssd_get_raop_servname(dnssd_t *dnssd) {
    return dnssd->raop_servname;
}

unsigned short android_dnssd_get_raop_port(dnssd_t *dnssd) {
    return dnssd->raop_port;
}

unsigned short android_dnssd_get_airplay_port(dnssd_t *dnssd) {
    return dnssd->airplay_port;
}
