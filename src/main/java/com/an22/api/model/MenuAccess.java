package com.an22.api.model;

import java.util.List;

/** {@code MenuAccess} (menu-access.model.ts): เมนูที่ role user เห็น. */
public record MenuAccess(List<String> userMenus) {}
