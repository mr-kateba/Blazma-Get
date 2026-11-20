"use strict";
export default class Logger {
    constructor() {
        // Off by default: logging every request and every config sync slows the browser down.
        this.loggingEnabled = false;
    }

    log(content) {
        if (this.loggingEnabled) {
            console.log(content);
        }
    }
}